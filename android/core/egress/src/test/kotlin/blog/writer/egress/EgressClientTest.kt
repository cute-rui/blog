package blog.writer.egress

import blog.wechat.egress.v1.AddDraftRequest
import blog.wechat.egress.v1.AddDraftResponse
import blog.wechat.egress.v1.UpdateDraftRequest
import blog.wechat.egress.v1.UpdateDraftResponse
import blog.wechat.egress.v1.UploadCoverRequest
import blog.wechat.egress.v1.UploadCoverResponse
import blog.wechat.egress.v1.UploadImageRequest
import blog.wechat.egress.v1.UploadImageResponse
import blog.wechat.egress.v1.WechatEgressGrpc
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.ServerInterceptors
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.stub.StreamObserver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class EgressClientTest {
    @Test
    fun bearerOnEveryRpcAndFailover() {
        val opened = mutableListOf<String>()
        val opener = ChannelOpener { endpoint, insecure, token ->
            opened += "${endpoint.address()} insecure=$insecure $token"
            val down = endpoint.port == 1
            object : EgressCall {
                override fun uploadImage(content: ByteArray, filename: String): String {
                    if (down) throw Unavailable("down")
                    return "http://mmbiz.qpic.cn/body"
                }

                override fun uploadCover(content: ByteArray, filename: String): String {
                    if (down) throw Unavailable("down")
                    return "cover-media"
                }

                override fun addDraft(article: EgressArticle): String {
                    if (down) throw Unavailable("down")
                    assertEquals("http://mmbiz.qpic.cn/body", article.content)
                    return "draft-media"
                }

                override fun updateDraft(mediaId: String, article: EgressArticle) {
                    if (down) throw Unavailable("down")
                }

                override fun close() {}
            }
        }
        val client = dial(
            ClientConfig(
                token = "test-bearer-token",
                domain = "example.com",
                lookup = SrvDirectory {
                    listOf(Endpoint("down.example.com", 1), Endpoint("egress.example.com", 8443))
                },
                opener = opener,
            ),
        )
        assertEquals("cover-media", client.uploadCover(byteArrayOf(1), "cover.png"))
        assertEquals(
            listOf(
                "down.example.com:1 insecure=false test-bearer-token",
                "egress.example.com:8443 insecure=false test-bearer-token",
            ),
            opened,
        )
        assertEquals("draft-media", client.addDraft(EgressArticle(title = "标题", content = "http://mmbiz.qpic.cn/body", thumbMediaId = "cover-media")))
    }

    @Test
    fun insecureOnlyForLoopback() {
        val opener = ChannelOpener { _, _, _ -> error("should not dial") }
        try {
            dial(
                ClientConfig(
                    token = "test-bearer-token",
                    insecure = true,
                    lookup = SrvDirectory { listOf(Endpoint("egress.example.com", 8443)) },
                    domain = "example.com",
                    opener = opener,
                ),
            )
            fail("public insecure target was accepted")
        } catch (error: EgressException) {
            assertTrue(error.message!!.contains("loopback"))
        }
        dial(
            ClientConfig(
                token = "test-bearer-token",
                address = "127.0.0.1:9",
                insecure = true,
                opener = opener,
            ),
        )
        try {
            dial(ClientConfig(token = "short", address = "127.0.0.1:9", insecure = true, opener = opener))
            fail("short token was accepted")
        } catch (error: EgressException) {
            assertTrue(error.message!!.contains("16"))
        }
    }

    @Test
    fun plaintextChannelIsRefusedOffLoopback() {
        try {
            newChannel(Endpoint("1.2.3.4", 443), insecure = true, token = "test-bearer-token").shutdownNow()
            fail("public plaintext channel was built")
        } catch (error: EgressException) {
            assertTrue(error.message!!.contains("loopback"))
        }
        newChannel(Endpoint("127.0.0.1", 9), insecure = true, token = "test-bearer-token").shutdownNow()
        newChannel(Endpoint("egress.example.com", 443), insecure = false, token = "test-bearer-token").shutdownNow()
    }

    @Test
    fun inProcessCallSendsBearerAndCoverRpc() {
        val name = InProcessServerBuilder.generateName()
        val auth = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)
        val seen = mutableListOf<String>()
        var coverName = ""
        var draftThumb = ""
        var updated = ""
        val base = object : WechatEgressGrpc.WechatEgressImplBase() {
            override fun uploadImage(request: UploadImageRequest, responseObserver: StreamObserver<UploadImageResponse>) {
                responseObserver.onNext(UploadImageResponse.newBuilder().setUrl("http://mmbiz.qpic.cn/body").build())
                responseObserver.onCompleted()
            }

            override fun uploadCover(request: UploadCoverRequest, responseObserver: StreamObserver<UploadCoverResponse>) {
                coverName = request.filename
                responseObserver.onNext(UploadCoverResponse.newBuilder().setMediaId("cover-media").build())
                responseObserver.onCompleted()
            }

            override fun addDraft(request: AddDraftRequest, responseObserver: StreamObserver<AddDraftResponse>) {
                draftThumb = request.getArticles(0).thumbMediaId
                responseObserver.onNext(AddDraftResponse.newBuilder().setMediaId("draft-media").build())
                responseObserver.onCompleted()
            }

            override fun updateDraft(request: UpdateDraftRequest, responseObserver: StreamObserver<UpdateDraftResponse>) {
                updated = request.mediaId + ":" + request.article.title
                responseObserver.onNext(UpdateDraftResponse.getDefaultInstance())
                responseObserver.onCompleted()
            }
        }
        val service = ServerInterceptors.intercept(
            base,
            object : ServerInterceptor {
                override fun <ReqT, RespT> interceptCall(
                    call: ServerCall<ReqT, RespT>,
                    headers: Metadata,
                    next: ServerCallHandler<ReqT, RespT>,
                ): ServerCall.Listener<ReqT> {
                    seen += headers.get(auth).orEmpty() + " " + call.methodDescriptor.bareMethodName
                    return next.startCall(call, headers)
                }
            },
        )
        val server = InProcessServerBuilder.forName(name).directExecutor().addService(service).build().start()
        val channel = InProcessChannelBuilder.forName(name).directExecutor().intercept(bearerInterceptor("test-bearer-token")).build()
        val rpc = GrpcCall(channel)
        try {
            assertEquals("cover-media", rpc.uploadCover(byteArrayOf(9), "cover.png"))
            assertEquals("cover.png", coverName)
            val added = rpc.addDraft(EgressArticle(title = "标题", content = "<p>a</p>", thumbMediaId = "cover-media"))
            assertEquals("draft-media", added)
            assertEquals("cover-media", draftThumb)
            rpc.updateDraft("draft-media", EgressArticle(title = "新标题", content = "<p>b</p>", thumbMediaId = "cover-media"))
            assertEquals("draft-media:新标题", updated)
            assertEquals(
                listOf(
                    "Bearer test-bearer-token UploadCover",
                    "Bearer test-bearer-token AddDraft",
                    "Bearer test-bearer-token UpdateDraft",
                ),
                seen,
            )
            assertTrue(seen.none { it.contains("api.weixin.qq.com") })
        } finally {
            rpc.close()
            server.shutdownNow()
        }
    }
}
