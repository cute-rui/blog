package blog.writer.sync
import blog.writer.copy.Copy

fun planPull(
    remoteFiles: List<RemoteFile>,
    localFiles: List<LocalSyncFile>,
): List<PullAction> {
    val remote = remoteFiles.filter { ContentPaths.isSyncPath(it.path) }.associateBy { it.path }
    val local = localFiles.associateBy { it.path }
    val actions = mutableListOf<PullAction>()
    for (path in (remote.keys + local.keys).sorted()) {
        val remoteFile = remote[path]
        val localFile = local[path]
        actions += when {
            localFile == null && remoteFile != null -> PullAction.Download(path, remoteFile.blobSha)
            localFile != null && remoteFile == null -> when {
                localFile.baseBlobSha == null -> PullAction.LocalOnly(path)
                isDirty(localFile) -> PullAction.Conflict(path, null)
                else -> PullAction.DeleteLocal(path)
            }
            localFile != null && remoteFile != null -> classifyBoth(localFile, remoteFile)
            else -> error(Copy.format("path_on_neither_side", "path" to path))
        }
    }
    return actions
}

private fun classifyBoth(localFile: LocalSyncFile, remoteFile: RemoteFile): PullAction {
    val localSha = GitBlob.sha1(localFile.bytes)
    if (localSha == remoteFile.blobSha) return PullAction.InSync(localFile.path, remoteFile.blobSha)
    val dirty = isDirty(localFile)
    val remoteMoved = localFile.baseBlobSha != remoteFile.blobSha
    return when {
        dirty && remoteMoved -> PullAction.Conflict(localFile.path, remoteFile.blobSha)
        dirty -> PullAction.LocalOnly(localFile.path)
        else -> PullAction.Download(localFile.path, remoteFile.blobSha)
    }
}

private fun isDirty(file: LocalSyncFile): Boolean {
    if (file.baseBlobSha == null) return true
    return GitBlob.sha1(file.bytes) != file.baseBlobSha
}

class SyncEngine {
    suspend fun pull(
        api: GitHubContentApi,
        branch: String,
        local: List<LocalSyncFile>,
    ): PullOutcome {
        val head = api.head(branch)
        val commit = api.commit(head)
        val remote = api.tree(commit.treeSha)
        val actions = planPull(remote, local)
        val downloads = actions.mapNotNull { action ->
            when (action) {
                is PullAction.Download -> DownloadedFile(action.path, action.blobSha, api.readBlob(action.blobSha))
                else -> null
            }
        }
        return PullOutcome(head, actions, downloads)
    }

    suspend fun push(api: GitHubContentApi, request: PushRequest): PushResult {
        val head = try {
            api.head(request.branch)
        } catch (error: GitHubException) {
            return PushResult.Failed(error.message ?: Copy["read_branch_failed"])
        }
        if (head != request.baseCommitSha) return PushResult.RemoteMoved(head)
        return try {
            val base = api.commit(head)
            val changes = buildList {
                for (file in request.upserts) {
                    add(TreeEntry(file.path, api.createBlob(file.bytes)))
                }
                for (path in request.deletes) {
                    add(TreeEntry(path, null))
                }
            }
            val tree = api.createTree(base.treeSha, changes)
            val commit = api.createCommit(head, tree, request.message)
            when (val update = api.updateRef(request.branch, commit, force = false)) {
                is RefUpdate.Updated -> PushResult.Pushed(update.sha, request.message)
                is RefUpdate.Rejected -> PushResult.RemoteMoved(update.currentSha)
            }
        } catch (error: GitHubException) {
            PushResult.Failed(error.message ?: Copy["commit_failed"])
        }
    }
}
