package blog.writer.sync

data class RemoteCommit(
    val sha: String,
    val treeSha: String,
    val message: String,
)

data class RemoteFile(
    val path: String,
    val blobSha: String,
    val mode: String = "100644",
)

data class TreeEntry(
    val path: String,
    val blobSha: String?,
)

sealed interface RefUpdate {
    data class Updated(val sha: String) : RefUpdate
    data class Rejected(val currentSha: String) : RefUpdate
}

data class LocalSyncFile(
    val path: String,
    val bytes: ByteArray,
    val baseBlobSha: String?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LocalSyncFile) return false
        return path == other.path && baseBlobSha == other.baseBlobSha && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = path.hashCode()
        result = 31 * result + bytes.contentHashCode()
        result = 31 * result + (baseBlobSha?.hashCode() ?: 0)
        return result
    }
}

data class PushFile(
    val path: String,
    val bytes: ByteArray,
)

data class PushRequest(
    val branch: String,
    val baseCommitSha: String,
    val message: String,
    val upserts: List<PushFile>,
    val deletes: List<String>,
)

sealed interface PushResult {
    data class Pushed(val commitSha: String, val message: String) : PushResult
    data class RemoteMoved(val currentSha: String) : PushResult
    data class Failed(val message: String) : PushResult
}

sealed interface PullAction {
    data class InSync(val path: String, val blobSha: String) : PullAction
    data class Download(val path: String, val blobSha: String) : PullAction
    data class DeleteLocal(val path: String) : PullAction
    data class Conflict(val path: String, val remoteBlobSha: String?) : PullAction
    data class LocalOnly(val path: String) : PullAction
}

data class DownloadedFile(
    val path: String,
    val blobSha: String,
    val bytes: ByteArray,
)

data class PullOutcome(
    val headSha: String,
    val actions: List<PullAction>,
    val downloads: List<DownloadedFile>,
)

class GitHubException(message: String, val status: Int? = null) : RuntimeException(message)

interface GitHubContentApi {
    suspend fun head(branch: String): String

    suspend fun commit(sha: String): RemoteCommit

    suspend fun tree(treeSha: String): List<RemoteFile>

    suspend fun readBlob(sha: String): ByteArray

    suspend fun createBlob(bytes: ByteArray): String

    suspend fun createTree(baseTreeSha: String, changes: List<TreeEntry>): String

    suspend fun createCommit(parentSha: String, treeSha: String, message: String): String

    suspend fun updateRef(branch: String, sha: String, force: Boolean): RefUpdate
}

interface GitHubActionsApi {
    suspend fun latestRuns(limit: Int = 5): List<WorkflowRunSummary>
}

data class WorkflowRunSummary(
    val id: Long,
    val status: String,
    val conclusion: String?,
    val htmlUrl: String,
    val name: String,
    val jobs: List<JobSummary> = emptyList(),
)

data class JobSummary(
    val name: String,
    val status: String,
    val conclusion: String?,
)
