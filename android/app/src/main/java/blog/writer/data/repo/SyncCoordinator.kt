package blog.writer.data.repo
import blog.writer.copy.Copy

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import blog.writer.content.PublishSelection
import blog.writer.content.commitMessage
import blog.writer.data.files.LocalContentStore
import blog.writer.sync.GitBlob
import blog.writer.sync.GitHubRestClient
import blog.writer.sync.MergePiece
import blog.writer.sync.MergeResult
import blog.writer.sync.PullAction
import blog.writer.sync.PushFile
import blog.writer.sync.PushRequest
import blog.writer.sync.PushResult
import blog.writer.sync.SyncEngine
import blog.writer.sync.mergeParagraphs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

data class OpenConflict(
    val path: String,
    val remoteSha: String?,
    val pieces: List<MergePiece>?,
    val remoteText: String?,
)

data class SyncSnapshot(
    val syncing: Boolean = false,
    val offline: Boolean = false,
    val message: String = Copy["synced"],
    val conflicts: List<OpenConflict> = emptyList(),
)

@Singleton
class SyncCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val posts: PostRepository,
    private val github: GithubSettingsRepository,
    private val files: LocalContentStore,
    private val http: OkHttpClient,
    private val engine: SyncEngine,
) {
    private val _state = MutableStateFlow(SyncSnapshot())
    val state: StateFlow<SyncSnapshot> = _state.asStateFlow()

    fun online(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    suspend fun sync(selection: PublishSelection? = null, onlyPaths: Set<String>? = null): String {
        if (!online()) {
            _state.value = SyncSnapshot(offline = true, message = Copy["offline_saved"])
            return Copy["offline_kept"]
        }
        val settings = github.current()
        val token = github.token()
        if (settings.owner.isBlank() || settings.repo.isBlank() || token.isNullOrBlank()) {
            _state.value = SyncSnapshot(message = Copy["github_not_configured"])
            return Copy["github_local_only"]
        }
        _state.value = _state.value.copy(syncing = true, message = Copy["syncing"])
        val client = GitHubRestClient(http, settings.owner, settings.repo, token)
        return try {
            val pulled = pull(client, settings.branch)
            if (pulled != null) return pulled
            if (selection == null && onlyPaths == null && posts.localFiles().none { isDirtyFile(it) }) {
                _state.value = SyncSnapshot(message = Copy["synced"])
                return Copy["synced"]
            }
            push(client, settings.branch, selection ?: PublishSelection(blog = false, wechat = false), onlyPaths)
        } catch (error: Exception) {
            _state.value = SyncSnapshot(message = error.message ?: Copy["sync_failed"])
            error.message ?: Copy["sync_failed"]
        }
    }

    suspend fun resolve(path: String, chooseLocal: List<Boolean>) {
        val conflict = _state.value.conflicts.firstOrNull { it.path == path } ?: return
        val merged = if (conflict.pieces != null) {
            MergeResult(conflict.pieces).render { index -> chooseLocal.getOrElse(index) { true } }
        } else if (chooseLocal.firstOrNull() == false) {
            conflict.remoteText.orEmpty()
        } else {
            files.read(path).orEmpty()
        }
        if (path.endsWith(".md")) posts.writeBody(path, stripFrontMatterBody(path, merged))
        _state.value = _state.value.copy(conflicts = _state.value.conflicts.filterNot { it.path == path })
    }

    private suspend fun pull(client: GitHubRestClient, branch: String): String? {
        val outcome = engine.pull(client, branch, posts.localFiles())
        val conflicts = mutableListOf<OpenConflict>()
        for (action in outcome.actions) {
            when (action) {
                is PullAction.Download -> {
                    val bytes = outcome.downloads.first { it.path == action.path }.bytes
                    posts.applyRemote(action.path, bytes, action.blobSha)
                }
                is PullAction.DeleteLocal -> posts.deleteLocal(action.path)
                is PullAction.InSync -> {
                    val bytes = files.readBytes(action.path)
                    if (bytes != null) posts.applyRemote(action.path, bytes, action.blobSha)
                }
                is PullAction.LocalOnly -> Unit
                is PullAction.Conflict -> conflicts += buildConflict(client, action.path, action.remoteBlobSha)
            }
        }
        github.rememberHead(outcome.headSha, System.currentTimeMillis())
        if (conflicts.isNotEmpty()) {
            _state.value = SyncSnapshot(message = Copy["has_conflict"], conflicts = conflicts)
            return Copy["remote_choice"]
        }
        _state.value = _state.value.copy(syncing = false, conflicts = emptyList())
        return null
    }

    private suspend fun push(
        client: GitHubRestClient,
        branch: String,
        selection: PublishSelection,
        onlyPaths: Set<String>?,
    ): String {
        val head = github.current().baseCommitSha ?: return Copy["never_pulled"]
        val filesToSend = posts.localFiles().filter { file ->
            val selected = onlyPaths == null || file.path in onlyPaths
            selected && isDirtyFile(file)
        }
        if (filesToSend.isEmpty()) {
            _state.value = SyncSnapshot(message = Copy["synced"])
            return Copy["nothing_to_commit"]
        }
        val summary = if (filesToSend.size == 1) {
            Copy.format("update_one", "path" to filesToSend.first().path)
        } else {
            Copy.format("update_many", "count" to filesToSend.size)
        }
        val result = engine.push(
            client,
            PushRequest(
                branch = branch,
                baseCommitSha = head,
                message = commitMessage(summary, selection),
                upserts = filesToSend.map { PushFile(it.path, it.bytes) },
                deletes = emptyList(),
            ),
        )
        return when (result) {
            is PushResult.Pushed -> {
                for (file in filesToSend) {
                    if (file.path.endsWith(".md")) {
                        posts.markPublished(file.path, blog.writer.sync.ContentHash.sha256(file.bytes), GitBlob.sha1(file.bytes))
                    }
                    files.writeBase(file.path, file.bytes)
                }
                github.rememberHead(result.commitSha, System.currentTimeMillis())
                _state.value = SyncSnapshot(message = Copy["synced"])
                Copy["committed"]
            }
            is PushResult.RemoteMoved -> {
                github.rememberHead(result.currentSha, System.currentTimeMillis())
                _state.value = SyncSnapshot(message = Copy["has_conflict"])
                Copy["remote_moved"]
            }
            is PushResult.Failed -> {
                _state.value = SyncSnapshot(message = result.message)
                result.message
            }
        }
    }

    private suspend fun buildConflict(client: GitHubRestClient, path: String, remoteSha: String?): OpenConflict {
        val remote = remoteSha?.let { client.readBlob(it).toString(Charsets.UTF_8) }
        val local = files.read(path).orEmpty()
        val base = files.readBase(path)?.toString(Charsets.UTF_8)
        val pieces = if (path.endsWith(".md") && remote != null && base != null) {
            mergeParagraphs(base, local, remote).pieces
        } else {
            null
        }
        return OpenConflict(path, remoteSha, pieces, remote)
    }

    private fun isDirtyFile(file: blog.writer.sync.LocalSyncFile): Boolean {
        if (file.baseBlobSha == null) return true
        return GitBlob.sha1(file.bytes) != file.baseBlobSha
    }

    private fun stripFrontMatterBody(path: String, mergedFile: String): String {
        return runCatching { blog.writer.content.FrontMatterCodec.decode(path, mergedFile).body }.getOrDefault(mergedFile)
    }
}
