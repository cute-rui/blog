package blog.writer.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "posts")
data class PostEntity(
    @PrimaryKey val path: String,
    val slug: String,
    val title: String,
    val description: String,
    val draft: Boolean,
    val publishBlog: Boolean,
    val publishWechat: Boolean,
    val wordCount: Int,
    val updatedAtEpochMs: Long,
    val dirty: Boolean,
    val baseBlobSha: String?,
    val pending: Boolean,
    val publishedHash: String?,
)

@Entity(tableName = "llm_profiles")
data class LlmProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val presetId: String,
    val basedOnPresetId: String?,
    val protocol: String,
    val baseUrl: String,
    val model: String,
    val contextLength: Int,
    val maxOutput: Int,
    val stream: Boolean,
    val jsonOutput: Boolean,
    val builtinSearch: Boolean,
    val connectTimeoutSec: Int,
    val firstTokenTimeoutSec: Int,
    val totalTimeoutSec: Int,
    val allowHttp: Boolean,
    val extraHeadersJson: String,
    val keyLast4: String?,
    val diverged: Boolean,
    val testOk: Boolean?,
    val testSummary: String?,
    val testAtEpochMs: Long?,
)

@Entity(tableName = "secrets")
data class SecretEntity(
    @PrimaryKey val id: String,
    val payloadBase64: String,
)

@Entity(tableName = "assignments")
data class AssignmentEntity(
    @PrimaryKey val id: Int = 1,
    val defaultProfileId: String?,
    val defaultModel: String?,
    val continueFollow: Boolean,
    val continueProfileId: String?,
    val continueModel: String?,
    val continueTemperature: Double,
    val fillFollow: Boolean = true,
    val fillProfileId: String? = null,
    val fillModel: String? = null,
    val fillTemperature: Double = 0.7,
    val fillTargetWords: Int = 600,
    val factFollow: Boolean = true,
    val factProfileId: String? = null,
    val factModel: String? = null,
    val factMode: String = "AUTO",
    val factMergeCount: Int = 2,
    val factPerClaim: Int = 5,
    val factMaxClaims: Int = 10,
    val factPreferredDomains: String = "",
    val factCostCap: Double? = null,
)

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val id: Int = 1,
    val owner: String,
    val repo: String,
    val branch: String,
    val blogOwner: String,
    val blogRepo: String,
    val baseCommitSha: String?,
    val lastPullEpochMs: Long?,
    val siteOrigin: String,
    val wxIndexUrl: String,
    val wechatName: String,
    val wechatMode: String,
)

@Entity(tableName = "pending_drafts")
data class PendingDraftEntity(
    @PrimaryKey val path: String,
    val offset: Int,
    val text: String,
)

@Entity(tableName = "search_profiles")
data class SearchProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val kind: String,
    val service: String,
    val baseUrl: String,
    val enabled: Boolean,
    val priority: Int,
    val needsInternational: Boolean,
    val timeoutSec: Int,
    val dailyLimit: Int?,
    val unitPrice: Double?,
    val method: String,
    val urlTemplate: String,
    val headersText: String,
    val bodyTemplate: String,
    val errorPath: String,
    val itemsPath: String,
    val fieldsText: String,
    val capFreshness: Boolean,
    val capIncludeDomains: Boolean,
    val capLanguage: Boolean,
    val capFullContent: Boolean,
    val keyLast4: String?,
    val testOk: Boolean?,
    val testSummary: String?,
    val testAtEpochMs: Long?,
    val lastTestFailed: Boolean,
)

@Entity(tableName = "ai_history")
data class AiHistoryEntity(
    @PrimaryKey val id: String,
    val action: String,
    val promptVersion: Int,
    val profileId: String,
    val model: String,
    val createdAtEpochMs: Long,
)

@Entity(tableName = "fill_marks")
data class FillMarkEntity(
    @PrimaryKey val path: String,
    val opening: String?,
    val ending: String?,
)

@Dao
interface WriterDao {
    @Query("SELECT * FROM posts ORDER BY updatedAtEpochMs DESC")
    fun observePosts(): Flow<List<PostEntity>>

    @Query("SELECT * FROM posts WHERE path = :path")
    suspend fun post(path: String): PostEntity?

    @Upsert
    suspend fun upsertPost(post: PostEntity)

    @Query("DELETE FROM posts WHERE path = :path")
    suspend fun deletePost(path: String)

    @Query("SELECT * FROM posts WHERE dirty = 1")
    suspend fun dirtyPosts(): List<PostEntity>

    @Query("SELECT path FROM posts")
    suspend fun postPaths(): List<String>

    @Query("UPDATE llm_profiles SET keyLast4 = NULL, testOk = NULL, testSummary = NULL")
    suspend fun clearProfileSecrets()

    @Query("SELECT * FROM llm_profiles ORDER BY name")
    fun observeProfiles(): Flow<List<LlmProfileEntity>>

    @Query("SELECT * FROM llm_profiles WHERE id = :id")
    suspend fun profile(id: String): LlmProfileEntity?

    @Upsert
    suspend fun upsertProfile(profile: LlmProfileEntity)

    @Query("DELETE FROM llm_profiles WHERE id = :id")
    suspend fun deleteProfile(id: String)

    @Query("SELECT * FROM secrets WHERE id = :id")
    suspend fun secret(id: String): SecretEntity?

    @Upsert
    suspend fun upsertSecret(secret: SecretEntity)

    @Query("DELETE FROM secrets WHERE id = :id")
    suspend fun deleteSecret(id: String)

    @Query("DELETE FROM secrets")
    suspend fun deleteSecrets()

    @Query("SELECT * FROM assignments WHERE id = 1")
    fun observeAssignments(): Flow<AssignmentEntity?>

    @Query("SELECT * FROM assignments WHERE id = 1")
    suspend fun assignments(): AssignmentEntity?

    @Upsert
    suspend fun upsertAssignments(entity: AssignmentEntity)

    @Query("SELECT * FROM sync_state WHERE id = 1")
    fun observeSync(): Flow<SyncStateEntity?>

    @Query("SELECT * FROM sync_state WHERE id = 1")
    suspend fun syncState(): SyncStateEntity?

    @Upsert
    suspend fun upsertSync(state: SyncStateEntity)

    @Query("SELECT * FROM pending_drafts WHERE path = :path")
    suspend fun pending(path: String): PendingDraftEntity?

    @Upsert
    suspend fun upsertPending(draft: PendingDraftEntity)

    @Query("DELETE FROM pending_drafts WHERE path = :path")
    suspend fun deletePending(path: String)

    @Query("SELECT path FROM pending_drafts")
    suspend fun pendingPaths(): List<String>

    @Query("SELECT * FROM search_profiles ORDER BY priority, name")
    fun observeSearch(): Flow<List<SearchProfileEntity>>

    @Query("SELECT * FROM search_profiles ORDER BY priority, name")
    suspend fun searchProfiles(): List<SearchProfileEntity>

    @Query("SELECT * FROM search_profiles WHERE id = :id")
    suspend fun searchProfile(id: String): SearchProfileEntity?

    @Upsert
    suspend fun upsertSearch(profile: SearchProfileEntity)

    @Query("DELETE FROM search_profiles WHERE id = :id")
    suspend fun deleteSearch(id: String)

    @Query("SELECT * FROM ai_history ORDER BY createdAtEpochMs DESC LIMIT 30")
    fun observeHistory(): Flow<List<AiHistoryEntity>>

    @Upsert
    suspend fun upsertHistory(entry: AiHistoryEntity)

    @Query("SELECT * FROM fill_marks WHERE path = :path")
    suspend fun fillMarks(path: String): FillMarkEntity?

    @Upsert
    suspend fun upsertFillMarks(marks: FillMarkEntity)
}

@Database(
    entities = [
        PostEntity::class,
        LlmProfileEntity::class,
        SecretEntity::class,
        AssignmentEntity::class,
        SyncStateEntity::class,
        PendingDraftEntity::class,
        SearchProfileEntity::class,
        AiHistoryEntity::class,
        FillMarkEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class WriterDatabase : RoomDatabase() {
    abstract fun dao(): WriterDao
}
