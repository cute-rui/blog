package blog.writer.data.repo

import blog.writer.ai.ActionAssignments
import blog.writer.ai.LlmClients
import blog.writer.ai.LlmEndpoint
import blog.writer.ai.LlmEvent
import blog.writer.ai.LlmRequest
import blog.writer.crypto.CredentialCipher
import blog.writer.data.db.AiHistoryEntity
import blog.writer.data.db.FillMarkEntity
import blog.writer.data.db.LlmProfileEntity
import blog.writer.data.db.SearchProfileEntity
import blog.writer.data.db.WriterDao
import blog.writer.search.BuiltinSearchAdapter
import blog.writer.search.BuiltinSearchService
import blog.writer.search.CitationFamily as SearchCitationFamily
import blog.writer.search.CustomHttpAdapter
import blog.writer.search.CustomHttpSpec
import blog.writer.search.FactCheckSearchSettings
import blog.writer.search.FollowingRedirects
import blog.writer.search.McpAuth
import blog.writer.search.McpAuthKind
import blog.writer.search.McpClient
import blog.writer.search.McpHandshake
import blog.writer.search.McpResults
import blog.writer.search.McpSearchAdapter
import blog.writer.search.ModelBuiltinSearchAdapter
import blog.writer.search.SearchCapabilities
import blog.writer.search.SearchMode
import blog.writer.search.SearchProfile
import blog.writer.search.SearchSourceKind
import blog.writer.search.ToolTextOrganizer
import blog.writer.search.hostOfUrl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import okhttp3.OkHttpClient
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SearchRepository @Inject constructor(
    private val dao: WriterDao,
    private val cipher: CredentialCipher,
    private val http: OkHttpClient,
    private val llm: LlmRepository,
    private val clients: LlmClients,
) {
    fun observe(): Flow<List<SearchProfileEntity>> = dao.observeSearch()

    suspend fun load(): List<SearchProfile> = dao.searchProfiles().mapNotNull { entity -> toRuntime(entity) }

    suspend fun loadForFactCheck(assignments: ActionAssignments): List<SearchProfile> {
        val entities = dao.searchProfiles()
        val saved = entities.mapNotNull { entity -> toRuntime(entity) }
        if (!assignments.factUseBuiltin) return saved
        val resolved = assignments.resolveFactCheck() ?: return saved
        if (entities.any { it.kind == "model" && it.enabled && it.llmProfileId == resolved.profileId }) return saved
        val profile = llm.profile(resolved.profileId) ?: return saved
        if (!profile.builtinSearch) return saved
        val family = SearchCitationFamily.from(profile.presetId, profile.protocol) ?: return saved
        val endpoint = llm.endpoint(resolved.profileId, resolved.model) ?: return saved
        return saved + virtualBuiltin(profile, endpoint, family, resolved.model)
    }

    suspend fun profile(id: String): SearchProfileEntity? = dao.searchProfile(id)

    suspend fun save(entity: SearchProfileEntity, apiKey: String?, secrets: Map<String, String>) {
        val stored = if (entity.kind == "mcp" && entity.mcpAuth == "headers") redactHeaders(entity) else entity
        dao.upsertSearch(stored.copy(keyLast4 = apiKey?.takeLast(4) ?: entity.keyLast4))
        if (apiKey != null) store("search:${entity.id}", apiKey)
        secrets.forEach { (name, value) -> store("search-secret:${entity.id}:$name", value) }
    }

    suspend fun delete(id: String) {
        dao.deleteSearch(id)
        dao.deleteSecret("search:$id")
    }

    suspend fun moveToFront(id: String) {
        val rows = dao.searchProfiles().sortedBy { it.priority }.toMutableList()
        val index = rows.indexOfFirst { it.id == id }
        if (index <= 0) return
        val moving = rows.removeAt(index)
        rows.add(0, moving)
        rows.forEachIndexed { position, row -> dao.upsertSearch(row.copy(priority = position)) }
    }

    suspend fun restoreDefaultOrder() {
        val rows = dao.searchProfiles().sortedWith(compareBy<SearchProfileEntity> { kindRank(it.kind) }.thenBy { it.name })
        rows.forEachIndexed { position, row -> dao.upsertSearch(row.copy(priority = position)) }
    }

    suspend fun mcpHandshake(id: String): McpHandshake {
        val entity = dao.searchProfile(id) ?: throw IllegalStateException("找不到这个搜索来源")
        return McpClient(http).handshake(entity.baseUrl, authOf(entity))
    }

    suspend fun move(id: String, delta: Int) {
        val rows = dao.searchProfiles().sortedBy { it.priority }.toMutableList()
        val index = rows.indexOfFirst { it.id == id }
        val target = index + delta
        if (index < 0 || target !in rows.indices) return
        val moving = rows.removeAt(index)
        rows.add(target, moving)
        rows.forEachIndexed { position, row -> dao.upsertSearch(row.copy(priority = position)) }
    }

    suspend fun runtime(id: String): SearchProfile? {
        val entity = dao.searchProfile(id) ?: return null
        return toRuntime(entity)
    }

    suspend fun recordTest(id: String, ok: Boolean, summary: String) {
        val entity = dao.searchProfile(id) ?: return
        dao.upsertSearch(
            entity.copy(
                testOk = ok,
                testSummary = summary,
                testAtEpochMs = System.currentTimeMillis(),
                lastTestFailed = !ok,
            ),
        )
    }

    private suspend fun toRuntime(entity: SearchProfileEntity): SearchProfile? {
        val adapter = when (entity.kind) {
            "custom" -> custom(entity)
            "mcp" -> mcp(entity)
            "model" -> modelBuiltin(entity) ?: return null
            else -> builtin(entity)
        }
        val kind = when (entity.kind) {
            "custom" -> SearchSourceKind.CUSTOM_HTTP
            "mcp" -> SearchSourceKind.MCP
            "model" -> SearchSourceKind.MODEL_BUILTIN
            else -> SearchSourceKind.BUILTIN
        }
        val host = when (entity.kind) {
            "model" -> hostOfUrl(llm.profile(entity.llmProfileId)?.baseUrl.orEmpty())
            "custom" -> hostOfUrl(entity.urlTemplate.substringBefore("?"))
            else -> hostOfUrl(entity.baseUrl)
        }.ifBlank { entity.service }
        return SearchProfile(
            id = entity.id,
            name = entity.name,
            kind = kind,
            host = host,
            enabled = entity.enabled,
            priority = entity.priority,
            needsInternationalNetwork = entity.needsInternational,
            lastTestFailed = entity.lastTestFailed,
            dailyLimit = entity.dailyLimit,
            dailyUsed = 0,
            unitPrice = entity.unitPrice,
            adapter = adapter,
        )
    }

    private suspend fun builtin(entity: SearchProfileEntity): BuiltinSearchAdapter {
        val service = BuiltinSearchService.entries.firstOrNull { it.name.equals(entity.service, ignoreCase = true) }
            ?: BuiltinSearchService.BOCHA
        val key = read("search:${entity.id}").orEmpty()
        val base = entity.baseUrl.ifBlank { service.defaultBaseUrl }
        return BuiltinSearchAdapter(http, service, key, base, entity.id, entity.name)
    }

    private suspend fun custom(entity: SearchProfileEntity): CustomHttpAdapter {
        val secrets = secretNames(entity).associateWith { name -> read("search-secret:${entity.id}:$name").orEmpty() }
        return CustomHttpAdapter(
            http,
            CustomHttpSpec(
                method = entity.method.ifBlank { "POST" },
                urlTemplate = entity.urlTemplate,
                headers = parseHeaders(entity.headersText),
                bodyTemplate = entity.bodyTemplate,
                errorPath = entity.errorPath.ifBlank { null },
                itemsPath = entity.itemsPath.ifBlank { "$.results" },
                fields = parseHeaders(entity.fieldsText),
                capabilities = SearchCapabilities(
                    freshness = entity.capFreshness,
                    includeDomains = entity.capIncludeDomains,
                    language = entity.capLanguage,
                    fullContent = entity.capFullContent,
                ),
                secrets = secrets,
            ),
            entity.id,
            entity.name,
        )
    }

    private suspend fun mcp(entity: SearchProfileEntity): McpSearchAdapter {
        val organizer = if (entity.useModelCleanup && entity.cleanupProfileId.isNotBlank()) {
            ToolTextOrganizer { text ->
                val profile = llm.profile(entity.cleanupProfileId) ?: return@ToolTextOrganizer emptyList()
                val endpoint = llm.endpoint(entity.cleanupProfileId, profile.model) ?: return@ToolTextOrganizer emptyList()
                val answer = complete(
                    endpoint,
                    "把下面工具返回的文本整理成 JSON 数组，每项含 title、url、snippet。只能使用文本里原样出现的网址。\n\n$text",
                )
                McpResults.parseOrganizer(answer)
            }
        } else {
            null
        }
        return McpSearchAdapter(
            client = McpClient(http),
            serverUrl = entity.baseUrl,
            auth = authOf(entity),
            toolName = entity.mcpTool,
            paramMap = entity.mcpParamMap,
            itemsPath = entity.itemsPath,
            fields = parseHeaders(entity.fieldsText),
            useModelCleanup = entity.useModelCleanup,
            organizer = organizer,
            profileId = entity.id,
            profileName = entity.name,
        )
    }

    private suspend fun modelBuiltin(entity: SearchProfileEntity): ModelBuiltinSearchAdapter? {
        val profile = llm.profile(entity.llmProfileId) ?: return null
        val family = SearchCitationFamily.from(profile.presetId, profile.protocol) ?: return null
        val model = entity.boundModel.ifBlank { profile.model }
        val endpoint = llm.endpoint(entity.llmProfileId, model) ?: return null
        return builtinAdapter(endpoint, family, model, entity.maxCitations, entity.id, entity.name)
    }

    private fun virtualBuiltin(
        profile: LlmProfileEntity,
        endpoint: LlmEndpoint,
        family: SearchCitationFamily,
        model: String,
    ): SearchProfile {
        val adapter = builtinAdapter(endpoint, family, model, 5, "fact-builtin:${profile.id}", "${profile.name} 内置联网")
        return SearchProfile(
            id = "fact-builtin:${profile.id}",
            name = "${profile.name} 内置联网",
            kind = SearchSourceKind.MODEL_BUILTIN,
            host = hostOfUrl(endpoint.baseUrl).ifBlank { profile.name },
            enabled = true,
            priority = Int.MAX_VALUE,
            needsInternationalNetwork = family.needsInternationalNetwork,
            lastTestFailed = false,
            dailyLimit = null,
            dailyUsed = 0,
            unitPrice = null,
            adapter = adapter,
        )
    }

    private fun builtinAdapter(
        endpoint: LlmEndpoint,
        family: SearchCitationFamily,
        model: String,
        maxCitations: Int,
        id: String,
        name: String,
    ) = ModelBuiltinSearchAdapter(
        http = http,
        family = family,
        baseUrl = endpoint.baseUrl,
        apiKey = endpoint.apiKey,
        model = model,
        maxCitations = maxCitations,
        profileId = id,
        profileName = name,
        redirects = FollowingRedirects(http),
    )

    private suspend fun authOf(entity: SearchProfileEntity): McpAuth {
        val key = read("search:${entity.id}").orEmpty()
        return when (entity.mcpAuth) {
            "bearer" -> McpAuth(McpAuthKind.BEARER, key)
            "headers" -> {
                val headers = parseHeaders(entity.headersText).mapValues { (name, value) ->
                    val secret = Regex("""\{\{\s*secret\.([A-Za-z0-9_]+)""").find(value)?.groupValues?.get(1)
                    if (secret != null) read("search-secret:${entity.id}:$secret").orEmpty() else value
                }
                McpAuth(McpAuthKind.HEADERS, headers = headers)
            }
            else -> McpAuth(McpAuthKind.NONE)
        }
    }

    private suspend fun redactHeaders(entity: SearchProfileEntity): SearchProfileEntity {
        val parsed = parseHeaders(entity.headersText)
        parsed.forEach { (name, value) ->
            if (!value.contains("{{")) store("search-secret:${entity.id}:$name", value)
        }
        val redacted = parsed.entries.joinToString("\n") { (name, value) ->
            if (value.contains("{{")) "$name=$value" else "$name={{secret.$name}}"
        }
        return entity.copy(headersText = redacted)
    }

    private suspend fun complete(endpoint: LlmEndpoint, user: String): String {
        val text = StringBuilder()
        clients.create(endpoint).stream(
            LlmRequest(endpoint.model, "你是检索结果整理器。", user, 0.1, 1024, endpoint.stream, false),
        ).collect { event ->
            when (event) {
                is LlmEvent.TextDelta -> text.append(event.text)
                is LlmEvent.Failed -> error(event.error.message)
                is LlmEvent.Done, is LlmEvent.Citation -> Unit
            }
        }
        return text.toString()
    }

    private fun kindRank(kind: String): Int = when (kind) {
        "custom" -> 1
        "mcp" -> 2
        "model" -> 3
        else -> 0
    }

    private fun secretNames(entity: SearchProfileEntity): List<String> {
        val blob = entity.urlTemplate + entity.bodyTemplate + entity.headersText
        return Regex("""\{\{\s*secret\.([A-Za-z0-9_]+)""").findAll(blob).map { it.groupValues[1] }.distinct().toList()
    }

    private suspend fun store(id: String, value: String) {
        val payload = cipher.encrypt(value.toByteArray(), aad = id.toByteArray())
        dao.upsertSecret(blog.writer.data.db.SecretEntity(id, Base64.getEncoder().encodeToString(payload)))
    }

    private suspend fun read(id: String): String? {
        val stored = dao.secret(id) ?: return null
        return cipher.decrypt(Base64.getDecoder().decode(stored.payloadBase64), aad = id.toByteArray()).toString(Charsets.UTF_8)
    }

    private fun parseHeaders(text: String): Map<String, String> =
        text.lineSequence().mapNotNull { line ->
            val index = line.indexOf('=')
            if (index <= 0) null else line.substring(0, index).trim() to line.substring(index + 1).trim()
        }.toMap()
}

fun factSettings(assignments: blog.writer.ai.ActionAssignments): FactCheckSearchSettings {
    val mode = when (assignments.factMode) {
        "SINGLE" -> SearchMode.SINGLE
        "MERGE" -> SearchMode.MERGE
        else -> SearchMode.AUTO
    }
    return FactCheckSearchSettings(
        mode = mode,
        singleProfileId = assignments.factSingleProfileId,
        mergeCount = assignments.factMergeCount,
        resultsPerClaim = assignments.factPerClaim,
        maxClaims = assignments.factMaxClaims,
        preferredDomains = assignments.factPreferredDomains.split(',').map { it.trim() }.filter { it.isNotEmpty() },
        costCap = assignments.factCostCap,
    )
}

@Singleton
class AiHistoryRepository @Inject constructor(
    private val dao: WriterDao,
) {
    fun observe() = dao.observeHistory()

    suspend fun record(action: String, profileId: String, model: String) {
        dao.upsertHistory(
            AiHistoryEntity(
                id = java.util.UUID.randomUUID().toString(),
                action = action,
                promptVersion = blog.writer.ai.Prompts.version,
                profileId = profileId,
                model = model,
                createdAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun marks(path: String): FillMarkEntity? = dao.fillMarks(path)

    suspend fun saveMarks(path: String, opening: String?, ending: String?) {
        dao.upsertFillMarks(FillMarkEntity(path, opening, ending))
    }
}
