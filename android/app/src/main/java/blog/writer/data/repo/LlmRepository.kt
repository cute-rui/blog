package blog.writer.data.repo

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import blog.writer.ai.ActionAssignments
import blog.writer.ai.LlmEndpoint
import blog.writer.ai.LlmProtocol
import blog.writer.ai.ModelChoice
import blog.writer.crypto.CredentialCipher
import blog.writer.data.db.AssignmentEntity
import blog.writer.data.db.LlmProfileEntity
import blog.writer.data.db.SecretEntity
import blog.writer.data.db.SyncStateEntity
import blog.writer.data.db.WriterDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

private val Context.appearanceStore by preferencesDataStore("appearance")

data class Appearance(
    val dynamicColor: Boolean = false,
    val haptics: Boolean = true,
    val serif: Boolean = true,
    val fontSizeSp: Float = 17f,
)

@Singleton
class AppearanceRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val appearance: Flow<Appearance> = context.appearanceStore.data.map { prefs ->
        Appearance(
            dynamicColor = prefs[Keys.dynamic] ?: false,
            haptics = prefs[Keys.haptics] ?: true,
            serif = prefs[Keys.serif] ?: true,
            fontSizeSp = prefs[Keys.font] ?: 17f,
        )
    }

    suspend fun update(block: (Appearance) -> Appearance) {
        context.appearanceStore.edit { prefs ->
            val current = Appearance(
                dynamicColor = prefs[Keys.dynamic] ?: false,
                haptics = prefs[Keys.haptics] ?: true,
                serif = prefs[Keys.serif] ?: true,
                fontSizeSp = prefs[Keys.font] ?: 17f,
            )
            val next = block(current)
            prefs[Keys.dynamic] = next.dynamicColor
            prefs[Keys.haptics] = next.haptics
            prefs[Keys.serif] = next.serif
            prefs[Keys.font] = next.fontSizeSp
        }
    }

    private object Keys {
        val dynamic = booleanPreferencesKey("dynamic_color")
        val haptics = booleanPreferencesKey("haptics")
        val serif = booleanPreferencesKey("serif")
        val font = floatPreferencesKey("font_size")
    }
}

@Singleton
class LlmRepository @Inject constructor(
    private val dao: WriterDao,
    private val cipher: CredentialCipher,
) {
    fun observeProfiles(): Flow<List<LlmProfileEntity>> = dao.observeProfiles()

    fun observeAssignments(): Flow<ActionAssignments> = dao.observeAssignments().map { it.toModel() }

    suspend fun profile(id: String): LlmProfileEntity? = dao.profile(id)

    suspend fun save(profile: LlmProfileEntity, newKey: String?) {
        val last4 = when {
            newKey != null -> newKey.takeLast(4)
            else -> profile.keyLast4
        }
        dao.upsertProfile(profile.copy(keyLast4 = last4))
        if (newKey != null) {
            val payload = cipher.encrypt(newKey.toByteArray(), aad = "llm:${profile.id}".toByteArray())
            dao.upsertSecret(SecretEntity("llm:${profile.id}", Base64.getEncoder().encodeToString(payload)))
        }
    }

    suspend fun apiKey(profileId: String): String? {
        val stored = dao.secret("llm:$profileId") ?: return null
        val payload = Base64.getDecoder().decode(stored.payloadBase64)
        return cipher.decrypt(payload, aad = "llm:$profileId".toByteArray()).toString(Charsets.UTF_8)
    }

    suspend fun delete(profileId: String) {
        dao.deleteProfile(profileId)
        dao.deleteSecret("llm:$profileId")
        val current = dao.assignments() ?: return
        if (current.continueProfileId == profileId) {
            dao.upsertAssignments(current.copy(continueFollow = true, continueProfileId = null, continueModel = null))
        }
    }

    suspend fun saveAssignments(assignments: ActionAssignments) {
        dao.upsertAssignments(assignments.toEntity())
    }

    suspend fun endpoint(profileId: String, model: String): LlmEndpoint? {
        val profile = dao.profile(profileId) ?: return null
        val key = apiKey(profileId) ?: return null
        return LlmEndpoint(
            protocol = when (profile.protocol) {
                "anthropic" -> LlmProtocol.Anthropic
                "gemini" -> LlmProtocol.Gemini
                else -> LlmProtocol.OpenAiCompatible
            },
            baseUrl = profile.baseUrl,
            apiKey = key,
            model = model,
            extraHeaders = parseHeaders(profile.extraHeadersJson),
            stream = profile.stream,
            jsonOutput = profile.jsonOutput,
            connectTimeoutSec = profile.connectTimeoutSec,
            firstTokenTimeoutSec = profile.firstTokenTimeoutSec,
            totalTimeoutSec = profile.totalTimeoutSec,
        )
    }

    suspend fun clearSecrets() {
        dao.deleteSecrets()
        dao.clearProfileSecrets()
    }

    private fun parseHeaders(json: String): Map<String, String> {
        if (json.isBlank()) return emptyMap()
        return json.lineSequence().mapNotNull { line ->
            val index = line.indexOf('=')
            if (index <= 0) null else line.substring(0, index) to line.substring(index + 1)
        }.toMap()
    }
}

fun headersToText(headers: Map<String, String>): String =
    headers.entries.joinToString("\n") { (key, value) -> "$key=$value" }

private fun AssignmentEntity?.toModel(): ActionAssignments {
    val row = this ?: return ActionAssignments()
    return ActionAssignments(
        defaultProfileId = row.defaultProfileId,
        defaultModel = row.defaultModel,
        continueWriting = ModelChoice(
            profileId = row.continueProfileId,
            model = row.continueModel,
            followDefault = row.continueFollow,
            temperature = row.continueTemperature,
        ),
        fillMiddle = ModelChoice(row.fillProfileId, row.fillModel, row.fillFollow, row.fillTemperature),
        fillTargetWords = row.fillTargetWords,
        factCheck = ModelChoice(row.factProfileId, row.factModel, row.factFollow, 0.1),
        factMode = row.factMode,
        factMergeCount = row.factMergeCount,
        factPerClaim = row.factPerClaim,
        factMaxClaims = row.factMaxClaims,
        factPreferredDomains = row.factPreferredDomains,
        factCostCap = row.factCostCap,
        factUseBuiltin = row.factUseBuiltin,
        factSingleProfileId = row.factSingleProfileId,
    )
}

private fun ActionAssignments.toEntity(): AssignmentEntity = AssignmentEntity(
    defaultProfileId = defaultProfileId,
    defaultModel = defaultModel,
    continueFollow = continueWriting.followDefault,
    continueProfileId = continueWriting.profileId,
    continueModel = continueWriting.model,
    continueTemperature = continueWriting.temperature,
    fillFollow = fillMiddle.followDefault,
    fillProfileId = fillMiddle.profileId,
    fillModel = fillMiddle.model,
    fillTemperature = fillMiddle.temperature,
    fillTargetWords = fillTargetWords,
    factFollow = factCheck.followDefault,
    factProfileId = factCheck.profileId,
    factModel = factCheck.model,
    factMode = factMode,
    factMergeCount = factMergeCount,
    factPerClaim = factPerClaim,
    factMaxClaims = factMaxClaims,
    factPreferredDomains = factPreferredDomains,
    factCostCap = factCostCap,
    factUseBuiltin = factUseBuiltin,
    factSingleProfileId = factSingleProfileId,
)

@Singleton
class GithubSettingsRepository @Inject constructor(
    private val dao: WriterDao,
    private val cipher: CredentialCipher,
) {
    fun observe(): Flow<SyncStateEntity> = dao.observeSync().map { it ?: SyncStateEntity(
        owner = "",
        repo = "",
        branch = "main",
        blogOwner = "",
        blogRepo = "",
        baseCommitSha = null,
        lastPullEpochMs = null,
        siteOrigin = "https://example.com",
        wxIndexUrl = "",
        wechatName = "",
        wechatMode = "html",
    ) }

    suspend fun current(): SyncStateEntity = dao.syncState() ?: SyncStateEntity(
        owner = "",
        repo = "",
        branch = "main",
        blogOwner = "",
        blogRepo = "",
        baseCommitSha = null,
        lastPullEpochMs = null,
        siteOrigin = "https://example.com",
        wxIndexUrl = "",
        wechatName = "",
        wechatMode = "html",
    )

    suspend fun save(state: SyncStateEntity, newToken: String?) {
        dao.upsertSync(state)
        if (newToken != null) {
            val payload = cipher.encrypt(newToken.toByteArray(), aad = "github".toByteArray())
            dao.upsertSecret(SecretEntity("github", Base64.getEncoder().encodeToString(payload)))
        }
    }

    suspend fun token(): String? {
        val stored = dao.secret("github") ?: return null
        return cipher.decrypt(Base64.getDecoder().decode(stored.payloadBase64), aad = "github".toByteArray())
            .toString(Charsets.UTF_8)
    }

    suspend fun tokenLast4(): String? = token()?.takeLast(4)

    suspend fun rememberHead(sha: String, pulledAt: Long) {
        dao.upsertSync(current().copy(baseCommitSha = sha, lastPullEpochMs = pulledAt))
    }

    suspend fun rememberMode(mode: String) {
        dao.upsertSync(current().copy(wechatMode = mode))
    }
}
