package blog.writer.search

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SearchTest {
    private val now = Instant.parse("2026-11-14T00:00:00Z")

    @Test
    fun normalizerDropsEmptyUrlsStripsTrackingAndHtml() {
        val response = SearchNormalizer.normalize(
            hits = listOf(
                RawHit("标题", null, "没地址", null, null, null, null, null),
                RawHit("<b>Kotlin</b>", "https://Example.com/a?utm_source=x&id=1#frag", "  <p>你好</p>  ", "正文", "2026-11-01", "站", null, 1.0),
            ),
            request = SearchRequest("q"),
            capabilities = SearchCapabilities(),
            origin = { rank -> SearchOrigin("p", "博查", "bocha", rank) },
            now = now,
        )
        assertEquals(1, response.results.size)
        val hit = response.results.single()
        assertEquals("https://example.com/a?id=1", hit.url)
        assertEquals("Kotlin", hit.title)
        assertEquals("你好", hit.snippet)
        assertEquals(Instant.parse("2026-11-01T00:00:00Z"), hit.publishedAt)
    }

    @Test
    fun normalizerFiltersDomainsLocallyAndDedupes() {
        val response = SearchNormalizer.normalize(
            hits = listOf(
                RawHit("甲", "https://spam.example/a", "短", null, "2026-11-13", null, null, null),
                RawHit("乙", "https://github.com/a", "短", null, "2020-01-01", null, null, null),
                RawHit("乙长", "https://github.com/a?utm_medium=x", "更长的摘录", "正文", "2026-11-10", null, null, null),
                RawHit("丙", "https://news.example/old", "旧", null, "2024-01-01", null, null, null),
            ),
            request = SearchRequest("q", includeDomains = listOf("github.com"), freshness = Freshness.MONTH),
            capabilities = SearchCapabilities(),
            origin = { SearchOrigin("p", "源", "custom", it) },
            now = now,
            preferredDomains = listOf("github.com"),
        )
        assertEquals(1, response.results.size)
        assertEquals("乙长", response.results.single().title)
        assertTrue(response.warnings.any { it.contains("本地过滤") })
    }

    @Test
    fun autoModeSkipsUnreachableAndDoesNotInventResults() = runBlocking {
        val memory = FailureMemory()
        val router = SearchRouter(
            probe = ReachabilityProbe { host -> host != "api.tavily.com" },
            memory = memory,
            now = { now },
        )
        val brave = profile("brave", "Brave", "api.search.brave.com", international = true, priority = 2, results = emptyList())
        val tavily = profile("tavily", "Tavily", "api.tavily.com", international = true, priority = 1, results = listOf(hit("https://tavily.example/a")))
        val bocha = profile("bocha", "博查", "api.bochaai.com", international = false, priority = 0, results = emptyList(), fail = "超时")
        val routed = router.search("Kotlin", listOf(tavily, brave, bocha), FactCheckSearchSettings())
        assertTrue(routed.failedAll)
        assertTrue(routed.results.isEmpty())
        assertEquals(listOf("博查", "Tavily", "Brave"), routed.attempts.map { it.profileName })
        assertEquals("当前不可达", routed.attempts[1].failure)
        assertTrue(routed.attempts.all { it.failure != null })
    }

    @Test
    fun autoModeUsesTheFirstSourceThatReturnsRows() = runBlocking {
        val router = SearchRouter(now = { now })
        val first = profile("bocha", "博查", "api.bochaai.com", false, 0, emptyList())
        val second = profile("brave", "Brave", "api.search.brave.com", false, 1, listOf(hit("https://brave.example/a")))
        val routed = router.search("q", listOf(first, second), FactCheckSearchSettings())
        assertFalse(routed.failedAll)
        assertEquals("https://brave.example/a", routed.results.single().url)
    }

    @Test
    fun threeFailuresOpenTheBreaker() = runBlocking {
        val memory = FailureMemory()
        val router = SearchRouter(memory = memory, now = { now })
        val broken = profile("bocha", "博查", "api.bochaai.com", false, 0, emptyList(), fail = "超时")
        repeat(3) { router.search("q", listOf(broken), FactCheckSearchSettings()) }
        val later = router.search("q", listOf(broken), FactCheckSearchSettings())
        assertTrue(later.attempts.isEmpty())
        assertTrue(later.failedAll)
    }

    @Test
    fun customTemplateMapsJsonAndKeepsSecretsOutOfTheTemplate() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse().setBody(
                    """{"data":{"results":[{"title":"题","link":"https://example.com/a?utm_source=z","summary":"<b>摘录</b>"}]}}""",
                ),
            )
            val adapter = CustomHttpAdapter(
                OkHttpClient(),
                CustomHttpSpec(
                    method = "POST",
                    urlTemplate = server.url("/v1/search").toString(),
                    headers = mapOf("Authorization" to "Bearer {{secret.apiKey}}"),
                    bodyTemplate = """{"q":"{{query}}","count":"{{maxResults}}","lang":"{{language|default:'zh-CN'}}"}""",
                    errorPath = "$.error.message",
                    itemsPath = "$.data.results",
                    fields = mapOf("title" to "$.title", "url" to "$.link", "snippet" to "$.summary"),
                    capabilities = SearchCapabilities(language = true),
                    secrets = mapOf("apiKey" to "secret-value"),
                ),
                "custom",
                "我的搜索",
            )
            val response = adapter.search(SearchRequest("Kotlin", language = null))
            val recorded = server.takeRequest()
            val payload = recorded.body.readUtf8()
            assertEquals("Bearer secret-value", recorded.getHeader("Authorization"))
            assertTrue(payload.contains("zh-CN"))
            assertFalse(payload.contains("secret-value"))
            assertEquals("摘录", response.results.single().snippet)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun builtinAdaptersParseTheirPayloads() = runBlocking {
        assertEquals("题", parseBocha().single().title)
        assertEquals("https://tavily.example/a", parseTavily().single().url)
        assertEquals("Brave 题", parseBrave().single().title)
        assertEquals("Searx 题", parseSearx().single().title)
    }

    private fun parseBocha() = serve(
        BuiltinSearchService.BOCHA,
        """{"data":{"webPages":{"value":[{"name":"题","url":"https://bocha.example/a","snippet":"摘","summary":"长","siteName":"站","datePublished":"2026-11-01"}]}}}""",
    )

    private fun parseTavily() = serve(
        BuiltinSearchService.TAVILY,
        """{"results":[{"title":"Tavily","url":"https://tavily.example/a","content":"正文","score":0.9}]}""",
    )

    private fun parseBrave() = serve(
        BuiltinSearchService.BRAVE,
        """{"web":{"results":[{"title":"Brave 题","url":"https://brave.example/a","description":"摘"}]}}""",
    )

    private fun parseSearx() = serve(
        BuiltinSearchService.SEARXNG,
        """{"results":[{"title":"Searx 题","url":"https://searx.example/a","content":"摘","publishedDate":"2026-11-02"}]}""",
    )

    private fun serve(service: BuiltinSearchService, body: String): List<SearchResult> = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody(body))
            val base = server.url("/").toString().trimEnd('/')
            val adapter = BuiltinSearchAdapter(OkHttpClient(), service, "k", base, service.name, service.label)
            adapter.search(SearchRequest("q")).results
        } finally {
            server.shutdown()
        }
    }

    private fun hit(url: String) = SearchResult(
        rank = 1,
        title = "题",
        url = url,
        snippet = "摘录",
        content = null,
        publishedAt = now,
        siteName = "站",
        language = null,
        score = null,
        origin = SearchOrigin("x", "x", "x", 1),
    )

    private fun profile(
        id: String,
        name: String,
        host: String,
        international: Boolean,
        priority: Int,
        results: List<SearchResult>,
        fail: String? = null,
    ) = SearchProfile(
        id = id,
        name = name,
        kind = SearchSourceKind.BUILTIN,
        host = host,
        enabled = true,
        priority = priority,
        needsInternationalNetwork = international,
        lastTestFailed = false,
        dailyLimit = null,
        dailyUsed = 0,
        unitPrice = null,
        adapter = object : SearchAdapter {
            override val id = id
            override val capabilities = SearchCapabilities()
            override suspend fun search(request: SearchRequest): SearchResponse {
                if (fail != null) throw SearchException(fail)
                return SearchResponse(results, emptySet(), emptyList(), 1)
            }
        },
    )
}
