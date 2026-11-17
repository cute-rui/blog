package blog.writer.egress
import blog.writer.copy.Copy

import blog.wechat.egress.v1.AddDraftRequest
import blog.wechat.egress.v1.Article
import blog.wechat.egress.v1.CoverCrop
import blog.wechat.egress.v1.UpdateDraftRequest
import blog.wechat.egress.v1.UploadCoverRequest
import blog.wechat.egress.v1.UploadImageRequest
import blog.wechat.egress.v1.WechatEgressGrpc
import com.google.protobuf.ByteString
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.okhttp.OkHttpChannelBuilder
import io.grpc.stub.MetadataUtils
import java.util.concurrent.TimeUnit

const val MAX_MESSAGE = 2 shl 20

private val authorization = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)

fun bearerInterceptor(token: String) =
    MetadataUtils.newAttachHeadersInterceptor(
        Metadata().apply { put(authorization, "Bearer $token") },
    )

fun newChannel(endpoint: Endpoint, insecure: Boolean, token: String): ManagedChannel {
    if (insecure && !isLoopbackHost(endpoint.host)) {
        throw EgressException(Copy.format("insecure_not_loopback", "host" to endpoint.host))
    }
    val builder = OkHttpChannelBuilder.forAddress(endpoint.host, endpoint.port)
        .intercept(bearerInterceptor(token))
        .maxInboundMessageSize(MAX_MESSAGE)
    if (insecure) {
        builder.usePlaintext()
    } else {
        builder.useTransportSecurity().overrideAuthority(endpoint.host)
    }
    return builder.build()
}

object GrpcChannelOpener : ChannelOpener {
    override fun open(endpoint: Endpoint, insecure: Boolean, token: String): EgressCall =
        GrpcCall(newChannel(endpoint, insecure, token))
}

internal class GrpcCall(private val channel: ManagedChannel) : EgressCall {
    private val stub = WechatEgressGrpc.newBlockingStub(channel).withDeadlineAfter(60, TimeUnit.SECONDS)

    override fun uploadImage(content: ByteArray, filename: String): String = invoke {
        val response = stub.uploadImage(
            UploadImageRequest.newBuilder()
                .setContent(ByteString.copyFrom(content))
                .setFilename(filename)
                .build(),
        )
        requireImageUrl(response.url)
    }

    override fun uploadCover(content: ByteArray, filename: String): String = invoke {
        val response = stub.uploadCover(
            UploadCoverRequest.newBuilder()
                .setContent(ByteString.copyFrom(content))
                .setFilename(filename)
                .build(),
        )
        val mediaId = response.mediaId
        if (mediaId.isBlank()) throw EgressException(Copy["cover_media_id_missing"])
        mediaId
    }

    override fun addDraft(article: EgressArticle): String = invoke {
        val response = stub.addDraft(
            AddDraftRequest.newBuilder().addArticles(article.toProto()).build(),
        )
        val mediaId = response.mediaId
        if (mediaId.isBlank()) throw EgressException(Copy["media_id_missing"])
        mediaId
    }

    override fun updateDraft(mediaId: String, article: EgressArticle) {
        invoke {
            stub.updateDraft(
                UpdateDraftRequest.newBuilder()
                    .setMediaId(mediaId)
                    .setIndex(0)
                    .setArticle(article.toProto())
                    .build(),
            )
        }
    }

    override fun close() {
        channel.shutdownNow()
    }
}

internal fun EgressArticle.toProto(): Article {
    val builder = Article.newBuilder()
        .setArticleType(articleType)
        .setTitle(title)
        .setAuthor(author)
        .setDigest(digest)
        .setContent(content)
        .setContentSourceUrl(contentSourceUrl)
        .setThumbMediaId(thumbMediaId)
    needOpenComment?.let { builder.setNeedOpenComment(it) }
    onlyFansCanComment?.let { builder.setOnlyFansCanComment(it) }
    for (crop in coverCrops) {
        builder.addCoverCrops(
            CoverCrop.newBuilder()
                .setRatio(crop.ratio)
                .setX1(crop.x1)
                .setY1(crop.y1)
                .setX2(crop.x2)
                .setY2(crop.y2)
                .build(),
        )
    }
    return builder.build()
}

private fun <T> invoke(block: () -> T): T {
    try {
        return block()
    } catch (error: StatusRuntimeException) {
        val message = error.status.description?.takeIf { it.isNotBlank() } ?: error.status.code.name
        if (error.status.code == Status.Code.UNAVAILABLE) throw Unavailable(message, error)
        throw EgressException(message, error)
    }
}
