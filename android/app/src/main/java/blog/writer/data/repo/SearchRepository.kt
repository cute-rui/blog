package blog.writer.data.repo

import blog.writer.crypto.CredentialCipher
import blog.writer.data.db.AiHistoryEntity
import blog.writer.data.db.FillMarkEntity
import blog.writer.data.db.SearchProfileEntity
import blog.writer.data.db.WriterDao
import blog.writer.search.BuiltinSearchAdapter
import blog.writer.search.BuiltinSearchService
import blog.writer.search.CustomHttpAdapter
import blog.writer.search.CustomHttpSpec
import blog.writer.search.FactCheckSearchSettings
import blog.writer.search.SearchCapabilities
import blog.writer.search.SearchMode
import blog.writer.search.SearchProfile
import blog.writer.search.SearchSourceKind
import blog.writer.search.hostOfUrl
import kotlinx.coroutines.flow.Flow
import okhttp3.OkHttpClient
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SearchRepository @Inject constructor(
    private val dao: WriterDao,
    private val cipher: CredentialCipher,
    private val http: OkHttpClient,
) {
    fun observe(): Flow<List<SearchProfileEntity>> = dao.observeSearch()

    suspend fun load(): List<SearchProfile> = dao.searchProfiles().mapNotNull { entity -> toRuntime(entity) }

    suspend fun profile(id: String): SearchProfileEntity? = dao.searchProfile(id)

    suspend fun save(entity: SearchProfileEntity, apiKey: String?, secrets: Map<String, String>) {
        dao.upsertSearch(entity.copy(keyLast4 = apiKey?.takeLast(4) ?: entity.keyLast4))
        if (apiKey != null) store("search:${entity.id}", apiKey)
        secrets.forEach { (name, value) -> store("search-secret:${entity.id}:$name", value) }
    }

    suspend fun delete(id: String) {
        dao.deleteSearch(id)
        dao.deleteSecret("search:$id")
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
        val adapter = if (entity.kind == "custom") custom(entity) else builtin(entity)
        val host = hostOfUrl(entity.baseUrl.ifBlank { entity.urlTemplate.substringBefore("?") }).ifBlank { entity.service }
        return SearchProfile(
            id = entity.id,
            name = entity.name,
            kind = if (entity.kind == "custom") SearchSourceKind.CUSTOM_HTTP else SearchSourceKind.BUILTIN,
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
        singleProfileId = assignments.factCheck.profileId,
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
