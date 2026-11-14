package blog.writer.ai

import blog.writer.search.FactCheckSearchSettings
import blog.writer.search.SearchAdapter
import blog.writer.search.SearchCapabilities
import blog.writer.search.SearchException
import blog.writer.search.SearchProfile
import blog.writer.search.SearchRequest
import blog.writer.search.SearchResponse
import blog.writer.search.SearchResult
import blog.writer.search.SearchOrigin
import blog.writer.search.SearchRouter
import blog.writer.search.SearchSourceKind
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AiTest {
    @Test
    fun presetsMatchThePlan() {
        assertEquals("https://api.deepseek.com", LlmPresets.deepSeek.baseUrl)
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1", LlmPresets.qwen.baseUrl)
        assertEquals("https://api.moonshot.cn/v1", LlmPresets.kimi.baseUrl)
        assertEquals("https://open.bigmodel.cn/api/paas/v4", LlmPresets.glm.baseUrl)
        assertEquals("https://ark.cn-beijing.volces.com/api/v3", LlmPresets.doubao.baseUrl)
        assertEquals("https://api.siliconflow.cn/v1", LlmPresets.siliconFlow.baseUrl)
        assertEquals("https://api.openai.com/v1", LlmPresets.openAi.baseUrl)
        assertEquals("https://api.anthropic.com", LlmPresets.anthropic.baseUrl)
        assertEquals(LlmProtocol.Anthropic, LlmPresets.anthropic.protocol)
        assertEquals("https://generativelanguage.googleapis.com", LlmPresets.gemini.baseUrl)
        assertEquals(LlmProtocol.Gemini, LlmPresets.gemini.protocol)
        assertEquals("https://openrouter.ai/api/v1", LlmPresets.openRouter.baseUrl)
        assertEquals(11, LlmPresets.all.size)
        assertTrue(LlmPresets.kimi.builtinSearch)
        assertFalse(LlmPresets.deepSeek.builtinSearch)
        assertEquals("自定义", LlmPresets.all.last().name)
        assertNull(validateBaseUrl("https://api.deepseek.com", allowHttp = false))
        assertTrue(validateBaseUrl("http://192.168.1.20:8080", allowHttp = false)!!.contains("https://"))
        assertNull(validateBaseUrl("http://192.168.1.20:8080", allowHttp = true))
    }

    @Test
    fun ideasParseFencedJsonAndStreamObjectByObject() {
        val raw = """
            好的。
            ```json
            [{"direction":"甲","sample":"第一段"},{"direction":"乙","sample":"第二段"}]
            ```
        """.trimIndent()
        val ideas = parseContinueIdeas(raw)
        assertEquals(listOf("甲", "乙"), ideas.map { it.direction })
        val parser = IdeaStreamParser()
        assertEquals("甲", parser.push("""[{"direction":"甲","sample":"第一段"}""").single().direction)
        assertTrue(parser.push(""" ,{"direction":"乙","sample":"第二段"}]""").single().direction == "乙")
        assertEquals(2, Prompts.version)
        assertTrue(Prompts.system.contains("Lightwing"))
        val prompt = Prompts.continueUser("标题", "大纲", "前", "后", "更具体", jsonMode = false)
        assertTrue(prompt.contains("不要用代码块包裹"))
        assertTrue(prompt.contains("补充要求：更具体"))
    }

    @Test
    fun assemblerDropsLaterContextBeforeTheNearPrefix() {
        val before = "远" + "。".repeat(20) + "\n\n" + "近" + "。".repeat(40)
        val body = before + "光标" + "后文".repeat(40)
        val cursor = before.length
        val assembled = assembleContext(
            title = "题",
            description = "述",
            tags = listOf("工程"),
            body = body,
            cursor = cursor,
            budgets = ContextBudgets(meta = 200, outline = 500, before = 1_500, after = 500),
            contextCharLimit = 30,
        )
        assertTrue(assembled.dropped.first() == "后文")
        assertTrue(assembled.after.isEmpty())
        assertTrue(assembled.before.endsWith("近") || assembled.before.contains("近") || assembled.dropped.contains("前文的远端"))
    }

    @Test
    fun continueFollowsTheDefaultUntilOverridden() {
        val followed = ActionAssignments(
            defaultProfileId = "deepseek",
            defaultModel = "deepseek-chat",
        ).resolveContinue()
        assertEquals("deepseek-chat", followed?.model)
        assertEquals(0.9, followed?.temperature)
        val pinned = ActionAssignments(
            defaultProfileId = "deepseek",
            defaultModel = "deepseek-chat",
            continueWriting = ModelChoice("kimi", "kimi-k2", followDefault = false, temperature = 1.1),
        ).resolveContinue()
        assertEquals("kimi-k2", pinned?.model)
        assertNull(ActionAssignments().resolveContinue())
    }

    @Test
    fun retryPolicySkipsAuthFailures() {
        assertFalse(shouldRetryLlm(HttpCallException(401, "bad"), 0))
        assertFalse(shouldRetryLlm(HttpCallException(403, "nope"), 0))
        assertTrue(shouldRetryLlm(IOException("reset"), 0))
        assertFalse(shouldRetryLlm(IOException("reset"), 1))
        assertEquals("key 错误或已失效", explainHttpStatus(401))
        assertEquals("无权限", explainHttpStatus(403))
        assertEquals("地址或模型名错", explainHttpStatus(404))
        assertEquals("被限流，稍后再试", explainHttpStatus(429))
    }

    @Test
    fun openAiStreamParsesDeltasAndRetriesADroppedConnection() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(
                        """
                        data: {"model":"deepseek-chat","choices":[{"delta":{"content":"你"}}]}

                        data: {"model":"deepseek-chat","choices":[{"delta":{"content":"好"}}]}

                        data: [DONE]

                        """.trimIndent(),
                    ),
            )
            val endpoint = LlmEndpoint(
                protocol = LlmProtocol.OpenAiCompatible,
                baseUrl = server.url("/v1").toString().trimEnd('/'),
                apiKey = "test-key",
                model = "deepseek-chat",
                firstTokenTimeoutSec = 5,
                totalTimeoutSec = 10,
            )
            val events = mutableListOf<LlmEvent>()
            LlmClients(OkHttpClient()).create(endpoint).stream(
                LlmRequest(
                    model = "deepseek-chat",
                    system = "sys",
                    user = "hi",
                    temperature = 0.9,
                    maxOutputTokens = 32,
                    stream = true,
                    jsonObject = false,
                ),
            ).collect { events += it }
            val text = events.filterIsInstance<LlmEvent.TextDelta>().joinToString("") { it.text }
            assertEquals("你好", text)
            val done = events.filterIsInstance<LlmEvent.Done>().single()
            assertEquals("deepseek-chat", done.model)
            assertEquals(2, server.requestCount)
            val recorded = listOf(server.takeRequest(), server.takeRequest())
            val authed = recorded.first { it.getHeader("Authorization") != null }
            assertEquals("Bearer test-key", authed.getHeader("Authorization"))
            assertTrue(authed.path!!.endsWith("/chat/completions"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun authFailureIsNotRetried() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}"""))
            val endpoint = LlmEndpoint(
                protocol = LlmProtocol.OpenAiCompatible,
                baseUrl = server.url("/").toString().trimEnd('/'),
                apiKey = "nope",
                model = "m",
                firstTokenTimeoutSec = 3,
                totalTimeoutSec = 5,
            )
            val events = mutableListOf<LlmEvent>()
            LlmClients(OkHttpClient()).create(endpoint).stream(
                LlmRequest("m", "s", "u", 0.0, 8, stream = true, jsonObject = false),
            ).collect { events += it }
            val failed = events.filterIsInstance<LlmEvent.Failed>().single()
            assertTrue(failed.error is LlmError.Http)
            assertEquals(401, (failed.error as LlmError.Http).status)
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun connectionTesterReadsAFakeClient() = runBlocking {
        val fake = FakeLlmProvider(
            listOf(
                LlmEvent.TextDelta("{\"ok\":true}"),
                LlmEvent.Done(usage = null, model = "deepseek-chat"),
            ),
        )
        val report = ConnectionTester().test(
            fake,
            LlmPresets.deepSeek.let { preset ->
                LlmEndpoint(
                    protocol = preset.protocol,
                    baseUrl = preset.baseUrl,
                    apiKey = "k",
                    model = preset.defaultModel,
                    stream = true,
                    jsonOutput = true,
                )
            },
        )
        assertTrue(report.ok)
        assertTrue(report.streamVerified)
        assertTrue(report.jsonVerified)
        assertFalse(report.modelMismatch)
        assertEquals("deepseek-chat", report.model)
    }

    @Test
    fun sseDecoderSplitsEvents() {
        val decoder = SseDecoder()
        assertNull(decoder.pushLine("data: {\"a\":1}"))
        val event = decoder.pushLine("")
        assertEquals("{\"a\":1}", event?.data)
    }

    @Test
    fun anthropicAndGeminiMappersReadText() {
        val box = ModelBox()
        val anthropic = mapAnthropic(
            SseEvent("content_block_delta", """{"type":"content_block_delta","delta":{"type":"text_delta","text":"甲"}}"""),
            box,
        )
        assertEquals("甲", (anthropic as LlmEvent.TextDelta).text)
        val gemini = mapGemini(
            SseEvent("", """{"modelVersion":"gemini-2.5-flash","candidates":[{"content":{"parts":[{"text":"乙"}]}}]}"""),
            box,
        )
        assertEquals("乙", (gemini as LlmEvent.TextDelta).text)
        assertEquals("gemini-2.5-flash", box.model)
    }

    @Test
    fun supportedWithoutSourcesIsDowngraded() {
        val sanitized = FactCheckRules.sanitize(
            claim = "Kotlin 2.0 在 2024 年发布",
            raw = RawJudgement("属实", "看起来对", emptyList(), null),
            sourceText = "Kotlin 2.0 在 2024 年发布。",
            resultCount = 2,
        )
        assertEquals(Verdict.Unverifiable, sanitized.verdict)
        val kept = FactCheckRules.sanitize(
            claim = "Kotlin 2.0 在 2024 年发布",
            raw = RawJudgement("错误", "年份不对", listOf(1, 9), "Kotlin 2.0 在 2024 年 5 月发布"),
            sourceText = "Kotlin 2.0 在 2024 年发布。",
            resultCount = 1,
        )
        assertEquals(listOf(1), kept.sourceIndexes)
        assertEquals(Verdict.Wrong, kept.verdict)
    }

    @Test
    fun failedSearchDoesNotAskTheModelToJudge() = runBlocking {
        var judged = false
        val checker = FactChecker(
            router = SearchRouter(),
            extract = { _, _ -> """[{"claim":"Kotlin 2.0 在 2024 年发布","query":"Kotlin 2.0"}]""" },
            judge = { _, _ ->
                judged = true
                """{"verdict":"属实","reason":"记忆","sources":[]}"""
            },
        )
        val report = checker.check(
            title = "题",
            text = "Kotlin 2.0 在 2024 年发布。",
            profiles = listOf(brokenProfile()),
            settings = FactCheckSearchSettings(),
        )
        assertFalse(judged)
        assertEquals(FactCheckRules.RETRIEVAL_FAILED, report.checked.single().label)
        assertTrue(report.checked.single().reason.contains("博查"))
    }

    @Test
    fun fillMarkerAndHistoryUseThePromptVersion() {
        val body = "开头段说明背景。\n\n<!-- fill -->\n\n结尾段给出结论。"
        val target = FillMiddle.target(body, null, null)
        assertEquals(body.indexOf("<!-- fill -->"), target?.insertAt)
        assertTrue(target!!.opening.endExclusive <= target.insertAt)
        val marked = FillMiddle.target("前文。开头段。后文。结尾段。", "开头段。", "结尾段。")
        assertEquals("开头段。".length + "前文。".length, marked?.insertAt)
        val entry = historyEntry("fact-check", "deepseek", "deepseek-chat", 1L)
        assertEquals(Prompts.version, entry.promptVersion)
        assertTrue(Prompts.fillUser("题", "纲", "开", "结", 600, null).contains("约 600 字"))
    }

    private fun brokenProfile() = SearchProfile(
        id = "bocha",
        name = "博查",
        kind = SearchSourceKind.BUILTIN,
        host = "api.bochaai.com",
        enabled = true,
        priority = 0,
        needsInternationalNetwork = false,
        lastTestFailed = false,
        dailyLimit = null,
        dailyUsed = 0,
        unitPrice = null,
        adapter = object : SearchAdapter {
            override val id = "bocha"
            override val capabilities = SearchCapabilities()
            override suspend fun search(request: SearchRequest): SearchResponse = throw SearchException("超时")
        },
    )
}
