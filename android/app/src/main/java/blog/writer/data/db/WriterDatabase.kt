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
}

@Database(
    entities = [
        PostEntity::class,
        LlmProfileEntity::class,
        SecretEntity::class,
        AssignmentEntity::class,
        SyncStateEntity::class,
        PendingDraftEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class WriterDatabase : RoomDatabase() {
    abstract fun dao(): WriterDao
}
