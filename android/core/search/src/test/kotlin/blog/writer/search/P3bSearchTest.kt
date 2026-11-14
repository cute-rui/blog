package blog.writer.search

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class P3bSearchTest {
    private val now = Instant.parse("2026-11-14T00:00:00Z")

    @Test
    fun builtinAdaptersParseVendorFixtures() = runBlocking {
        assertEquals("https://zhipu.example/a", serve(BuiltinSearchService.ZHIPU, "/web_search", zhipuBody).single().url)
        assertEquals("https://qianfan.example/a", serve(BuiltinSearchService.QIANFAN, "/v2/ai_search/web_search", qianfanBody).single().url)
        assertEquals("正文很长", serve(BuiltinSearchService.IQS, "/search/unified", iqsBody).single().content)
        assertEquals("https://exa.example/a", serve(BuiltinSearchService.EXA, "/search", exaBody).single().url)
        val serper = serve(BuiltinSearchService.SERPER, "/search", serperBody).single()
        assertEquals("https://serper.example/a", serper.url)
        val ageHours = java.time.Duration.between(serper.publishedAt, Instant.now()).toHours()
        assertTrue(ageHours in 47..49)
    }

    @Test
    fun vendorRequestsUseTheDocumentedAuthHeader() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody(exaBody))
            runBlocking {
                BuiltinSearchAdapter(OkHttpClient(), BuiltinSearchService.EXA, "exa-key", server.url("/").toString().trimEnd('/'), "exa", "Exa")
                    .search(SearchRequest("Kotlin"))
            }
            val recorded = server.takeRequest()
            assertEquals("exa-key", recorded.getHeader("x-api-key"))
            assertTrue(recorded.body.readUtf8().contains("Kotlin"))
            server.enqueue(MockResponse().setBody(serperBody))
            runBlocking {
                BuiltinSearchAdapter(OkHttpClient(), BuiltinSearchService.SERPER, "serper-key", server.url("/").toString().trimEnd('/'), "serper", "Serper")
                    .search(SearchRequest("Kotlin"))
            }
            assertEquals("serper-key", server.takeRequest().getHeader("X-API-KEY"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun mcpCallSendsOnlyTheQueryAndHonorsAuth() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            enqueueSession(server, toolJson())
            val bearer = McpSearchAdapter(
                McpClient(OkHttpClient()),
                server.url("/mcp").toString(),
                McpAuth(McpAuthKind.BEARER, "token-1"),
                "web_search",
                "query={{query}}\nmax_results={{maxResults}}",
                "",
                emptyMap(),
                false,
                null,
                "mcp",
                "MCP",
            )
            val results = bearer.search(SearchRequest("Kotlin", maxResults = 3)).results
            assertEquals("https://mcp.example/a", results.single().url)
            val init = server.takeRequest()
            assertEquals("Bearer token-1", init.getHeader("Authorization"))
            assertTrue(init.body.readUtf8().contains("initialize"))
            server.takeRequest()
            val call = server.takeRequest()
            assertEquals("sess-1", call.getHeader("Mcp-Session-Id"))
            val body = call.body.readUtf8()
            val arguments = Json.parseToJsonElement(body).jsonObject["params"]!!.jsonObject["arguments"]!!.jsonObject
            assertEquals(setOf("query", "max_results"), arguments.keys)
            assertEquals("Kotlin", arguments["query"]!!.jsonPrimitive.content)
            assertEquals(3, arguments["max_results"]!!.jsonPrimitive.content.toInt())
            assertFalse(body.contains("ARTICLE-BODY"))

            enqueueSession(server, toolJson())
            val custom = bearerWith(server, McpAuth(McpAuthKind.HEADERS, headers = mapOf("X-Token" to "abc")))
            custom.search(SearchRequest("q"))
            assertEquals("abc", server.takeRequest().getHeader("X-Token"))
            server.takeRequest()
            server.takeRequest()

            enqueueSession(server, toolJson())
            bearerWith(server, McpAuth(McpAuthKind.NONE)).search(SearchRequest("q"))
            assertNull(server.takeRequest().getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun mcpInitializeAcceptsSseAndListsSearchToolsFirst() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setHeader("Mcp-Session-Id", "sess-sse")
                    .setBody(
                        """
                        event: message
                        data: {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-03-26","serverInfo":{"name":"检索站","version":"1.2"}}}

                        """.trimIndent(),
                    ),
            )
            server.enqueue(MockResponse().setResponseCode(202))
            server.enqueue(
                MockResponse().setBody(
                    """{"jsonrpc":"2.0","id":2,"result":{"tools":[
                        {"name":"echo","description":"回声","inputSchema":{"type":"object","properties":{}}},
                        {"name":"web_search","description":"搜索网页","inputSchema":{"type":"object","properties":{"query":{"type":"string"},"count":{"type":"integer"}},"required":["query"]}}
                    ]}}""",
                ),
            )
            val handshake = McpClient(OkHttpClient()).handshake(server.url("/mcp").toString(), McpAuth(McpAuthKind.NONE))
            assertEquals("检索站", handshake.serverName)
            assertEquals("1.2", handshake.serverVersion)
            assertEquals("web_search", handshake.tools.first().name)
            assertTrue(handshake.tools.first().looksLikeSearch)
            assertEquals("query={{query}}\ncount={{maxResults}}", McpParams.autoMap(handshake.tools.first().inputSchema))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun modelCleanupDropsUrlsThatAreNotInTheToolText() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            enqueueSession(
                server,
                """{"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"请看 https://ok.example/a"}]}}""",
            )
            val adapter = McpSearchAdapter(
                McpClient(OkHttpClient()),
                server.url("/mcp").toString(),
                McpAuth(McpAuthKind.NONE),
                "web_search",
                "query={{query}}",
                "",
                emptyMap(),
                true,
                ToolTextOrganizer {
                    listOf(
                        RawHit("好", "https://ok.example/a", "摘录", null, null, null, null, null),
                        RawHit("坏", "https://evil.example/b", "摘录", null, null, null, null, null),
                    )
                },
                "mcp",
                "MCP",
            )
            val response = adapter.search(SearchRequest("Kotlin"))
            assertEquals(listOf("https://ok.example/a"), response.results.map { it.url })
            assertTrue(response.warnings.any { it.contains("丢弃") })
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun citationsIgnoreUrlsWrittenInTheModelText() = runBlocking {
        val anthropic = citation(
            CitationFamily.ANTHROPIC,
            """{"content":[
                {"type":"text","text":"见 https://evil.example/prose","citations":[
                    {"type":"web_search_result_location","url":"https://kotlinlang.org/docs","title":"Kotlin","cited_text":"Kotlin 2.0 于 2024 年发布，这一段引用足够长，不该标成摘录不完整。"}
                ]}
            ]}""",
        )
        assertEquals("https://kotlinlang.org/docs", anthropic.results.single().url)
        assertFalse(anthropic.results.single().incompleteExcerpt)

        val qwen = citation(
            CitationFamily.QWEN,
            """{"choices":[{"message":{"content":"https://evil.example/qwen"}}],"search_info":{"search_results":[{"title":"通义","url":"https://qwen.example/a"}]}}""",
        )
        assertEquals("https://qwen.example/a", qwen.results.single().url)
        assertTrue(qwen.results.single().incompleteExcerpt)
        assertTrue(qwen.warnings.any { it.contains("摘录不完整") })

        val zhipu = citation(
            CitationFamily.ZHIPU,
            """{"choices":[{"message":{"content":"https://evil.example/glm"}}],"web_search":[{"title":"智谱","link":"https://glm.example/a","content":"这是一段来自引用元数据的摘录，长度足够，不应该被标成不完整。","publish_date":"2026-11-01"}]}""",
        )
        assertEquals("https://glm.example/a", zhipu.results.single().url)

        val kimiEmpty = citation(
            CitationFamily.KIMI,
            """{"choices":[{"message":{"content":"https://evil.example/kimi","tool_calls":[{"function":{"name":"${'$'}web_search","arguments":"{\"url\":\"https://evil.example/tool\"}"}}]}}]}""",
        )
        assertTrue(kimiEmpty.results.isEmpty())

        val kimi = citation(
            CitationFamily.KIMI,
            """{"choices":[{"message":{"content":"https://evil.example/kimi","citations":[{"title":"Kimi","url":"https://kimi.example/a","snippet":"引用元数据里的摘录已经超过二十四字，所以这条不算摘录不完整。"}]}}]}""",
        )
        assertEquals("https://kimi.example/a", kimi.results.single().url)
    }

    @Test
    fun geminiResolvesRedirectCitationsAndIgnoresProse() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse().setBody(
                    """{"candidates":[{"content":{"parts":[{"text":"见 https://evil.example/prose"}]},"groundingMetadata":{"groundingChunks":[{"web":{"uri":"https://vertexaisearch.cloud.google.com/grounding-api-redirect/abc","title":"Kotlin"}}],"groundingSupports":[{"segment":{"text":"短"},"groundingChunkIndices":[0]}]}}]}""",
                ),
            )
            val adapter = ModelBuiltinSearchAdapter(
                OkHttpClient(),
                CitationFamily.GEMINI,
                server.url("/").toString().trimEnd('/'),
                "key",
                "gemini-2.5-flash",
                5,
                "gemini",
                "Gemini",
                RedirectResolver { "https://kotlinlang.org/docs" },
            )
            val response = adapter.search(SearchRequest("Kotlin"))
            assertEquals("https://kotlinlang.org/docs", response.results.single().url)
            assertTrue(response.results.single().incompleteExcerpt)
            assertTrue(response.detail!!.contains("跳转链接解析 ✓"))
            val request = server.takeRequest()
            assertTrue(request.path!!.contains("generateContent"))
            assertFalse(request.body.readUtf8().contains("evil.example"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun mergeRunsAtOnceDedupesAndFloatsPreferredDomains() = runBlocking {
        val started = AtomicInteger(0)
        val overlapped = AtomicInteger(0)
        fun source(id: String, name: String, priority: Int, rows: List<SearchResult>) = SearchProfile(
            id = id,
            name = name,
            kind = SearchSourceKind.BUILTIN,
            host = "$id.example",
            enabled = true,
            priority = priority,
            needsInternationalNetwork = false,
            lastTestFailed = false,
            dailyLimit = null,
            dailyUsed = 0,
            unitPrice = null,
            adapter = object : SearchAdapter {
                override val id = id
                override val capabilities = SearchCapabilities()
                override suspend fun search(request: SearchRequest): SearchResponse {
                    started.incrementAndGet()
                    val begin = System.nanoTime()
                    while (started.get() < 2 && System.nanoTime() - begin < 400_000_000) delay(10)
                    if (started.get() >= 2) overlapped.incrementAndGet()
                    return SearchResponse(rows, emptySet(), emptyList(), 1)
                }
            },
        )
        val rich = result("Exa", "https://github.com/a", "短", "更完整的正文")
        val poor = result("Serper", "https://github.com/a", "短", null)
        val other = result("Serper", "https://news.example/b", "另一条摘录", null)
        val router = SearchRouter(now = { now })
        val routed = router.search(
            "q",
            listOf(
                source("serper", "Serper", 0, listOf(poor, other)),
                source("exa", "Exa", 1, listOf(rich)),
            ),
            FactCheckSearchSettings(mode = SearchMode.MERGE, mergeCount = 2, preferredDomains = listOf("github.com")),
        )
        assertEquals(2, overlapped.get())
        assertEquals("https://github.com/a", routed.results.first().url)
        assertEquals("更完整的正文", routed.results.first().content)
        assertEquals("Exa", routed.results.first().origin.profileName)
        assertEquals(listOf("Serper"), routed.results.first().origin.alsoFrom)
        assertEquals("https://news.example/b", routed.results[1].url)
    }

    @Test
    fun autoModePrefersDedicatedSearchOverModelBuiltinWhenPriorityTies() = runBlocking {
        val router = SearchRouter(now = { now })
        val model = profile("model", "模型", SearchSourceKind.MODEL_BUILTIN, 0, listOf(result("模型", "https://model.example/a", "摘录", null)))
        val builtin = profile("bocha", "博查", SearchSourceKind.BUILTIN, 0, listOf(result("博查", "https://bocha.example/a", "摘录", null)))
        val routed = router.search("q", listOf(model, builtin), FactCheckSearchSettings())
        assertEquals("https://bocha.example/a", routed.results.single().url)
    }

    private fun bearerWith(server: MockWebServer, auth: McpAuth) = McpSearchAdapter(
        McpClient(OkHttpClient()),
        server.url("/mcp").toString(),
        auth,
        "web_search",
        "query={{query}}",
        "",
        emptyMap(),
        false,
        null,
        "mcp",
        "MCP",
    )

    private fun enqueueSession(server: MockWebServer, toolBody: String) {
        server.enqueue(
            MockResponse()
                .setHeader("Mcp-Session-Id", "sess-1")
                .setBody("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-03-26","serverInfo":{"name":"s","version":"1"}}}"""),
        )
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(MockResponse().setBody(toolBody))
    }

    private fun toolJson() =
        """{"jsonrpc":"2.0","id":3,"result":{"structuredContent":{"results":[{"title":"题","url":"https://mcp.example/a","snippet":"摘录"}]},"content":[{"type":"text","text":"https://evil.example/text"}]}}"""

    private suspend fun citation(family: CitationFamily, body: String): SearchResponse {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody(body))
            val adapter = ModelBuiltinSearchAdapter(
                OkHttpClient(),
                family,
                server.url("/").toString().trimEnd('/'),
                "key",
                "model",
                5,
                family.name,
                family.name,
            )
            return adapter.search(SearchRequest("Kotlin 发布"))
        } finally {
            server.shutdown()
        }
    }

    private fun serve(service: BuiltinSearchService, path: String, body: String): List<SearchResult> = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody(body))
            val adapter = BuiltinSearchAdapter(OkHttpClient(), service, "k", server.url("/").toString().trimEnd('/'), service.name, service.label)
            val results = adapter.search(SearchRequest("q")).results
            assertTrue(server.takeRequest().path!!.startsWith(path))
            results
        } finally {
            server.shutdown()
        }
    }

    private fun result(name: String, url: String, snippet: String, content: String?) = SearchResult(
        rank = 1,
        title = name,
        url = url,
        snippet = snippet,
        content = content,
        publishedAt = now,
        siteName = name,
        language = null,
        score = null,
        origin = SearchOrigin(name, name, name, 1),
    )

    private fun profile(id: String, name: String, kind: SearchSourceKind, priority: Int, rows: List<SearchResult>) = SearchProfile(
        id = id,
        name = name,
        kind = kind,
        host = "$id.example",
        enabled = true,
        priority = priority,
        needsInternationalNetwork = false,
        lastTestFailed = false,
        dailyLimit = null,
        dailyUsed = 0,
        unitPrice = null,
        adapter = object : SearchAdapter {
            override val id = id
            override val capabilities = SearchCapabilities()
            override suspend fun search(request: SearchRequest): SearchResponse = SearchResponse(rows, emptySet(), emptyList(), 1)
        },
    )

    private val zhipuBody =
        """{"search_result":[{"title":"智谱","link":"https://zhipu.example/a","content":"摘录","media":"站","publish_date":"2026-11-01"}]}"""
    private val qianfanBody =
        """{"references":[{"title":"千帆","url":"https://qianfan.example/a","content":"摘录","date":"2026-11-01","website":"百度"}]}"""
    private val iqsBody =
        """{"pageItems":[{"title":"IQS","link":"https://iqs.example/a","snippet":"摘录","mainText":"正文很长","publishedTime":"2026-11-01T00:00:00Z","hostname":"iqs.example"}]}"""
    private val exaBody =
        """{"results":[{"title":"Exa","url":"https://exa.example/a","text":"正文","publishedDate":"2026-11-01T00:00:00Z","score":0.4}]}"""
    private val serperBody =
        """{"organic":[{"title":"Serper","link":"https://serper.example/a","snippet":"摘录","date":"2 days ago"}]}"""
}
