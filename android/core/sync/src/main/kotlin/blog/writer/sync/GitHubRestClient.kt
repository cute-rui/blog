package blog.writer.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64

class GitHubRestClient(
    private val http: OkHttpClient,
    private val owner: String,
    private val repo: String,
    private val token: String,
    private val apiBase: String = "https://api.github.com",
) : GitHubContentApi, GitHubActionsApi {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = true
        encodeDefaults = true
    }

    override suspend fun head(branch: String): String {
        val body = get("/git/refs/heads/${encodeBranch(branch)}")
        return json.decodeFromString<RefPayload>(body).`object`.sha
    }

    override suspend fun commit(sha: String): RemoteCommit {
        val body = get("/git/commits/$sha")
        val payload = json.decodeFromString<CommitPayload>(body)
        return RemoteCommit(sha = payload.sha, treeSha = payload.tree.sha, message = payload.message)
    }

    override suspend fun tree(treeSha: String): List<RemoteFile> {
        val body = get("/git/trees/$treeSha?recursive=1")
        val payload = json.decodeFromString<TreePayload>(body)
        if (payload.truncated) throw GitHubException("仓库目录太大，这次没有列全")
        return payload.tree.mapNotNull { item ->
            val sha = item.sha ?: return@mapNotNull null
            if (item.type != "blob") return@mapNotNull null
            RemoteFile(path = item.path, blobSha = sha, mode = item.mode)
        }
    }

    override suspend fun readBlob(sha: String): ByteArray {
        return call("GET", "/git/blobs/$sha", body = null, accept = "application/vnd.github.raw")
    }

    override suspend fun createBlob(bytes: ByteArray): String {
        val payload = BlobWrite(content = Base64.getEncoder().encodeToString(bytes), encoding = "base64")
        val body = post("/git/blobs", json.encodeToString(payload))
        return json.decodeFromString<ShaPayload>(body).sha
    }

    override suspend fun createTree(baseTreeSha: String, changes: List<TreeEntry>): String {
        val payload = TreeWrite(
            baseTree = baseTreeSha,
            tree = changes.map { change ->
                TreeWriteItem(
                    path = change.path,
                    mode = "100644",
                    type = "blob",
                    sha = change.blobSha,
                )
            },
        )
        val body = post("/git/trees", json.encodeToString(payload))
        return json.decodeFromString<ShaPayload>(body).sha
    }

    override suspend fun createCommit(parentSha: String, treeSha: String, message: String): String {
        val payload = CommitWrite(message = message, tree = treeSha, parents = listOf(parentSha))
        val body = post("/git/commits", json.encodeToString(payload))
        return json.decodeFromString<ShaPayload>(body).sha
    }

    override suspend fun updateRef(branch: String, sha: String, force: Boolean): RefUpdate {
        val payload = json.encodeToString(RefWrite(sha = sha, force = force))
        val request = request("/git/refs/heads/${encodeBranch(branch)}")
            .patch(payload.toRequestBody(JSON))
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (response.code == 422) {
                val current = runCatching { head(branch) }.getOrDefault("")
                return RefUpdate.Rejected(current)
            }
            if (!response.isSuccessful) throw GitHubException(errorMessage(response.code, text), response.code)
            val updated = json.decodeFromString<RefPayload>(text)
            return RefUpdate.Updated(updated.`object`.sha)
        }
    }

    override suspend fun latestRuns(limit: Int): List<WorkflowRunSummary> {
        val body = get("/actions/runs?per_page=$limit")
        val payload = json.decodeFromString<RunsPayload>(body)
        return payload.workflowRuns.map { run ->
            val jobs = runCatching { jobs(run.id) }.getOrDefault(emptyList())
            WorkflowRunSummary(
                id = run.id,
                status = run.status,
                conclusion = run.conclusion,
                htmlUrl = run.htmlUrl,
                name = run.name ?: "",
                jobs = jobs,
            )
        }
    }

    private fun jobs(runId: Long): List<JobSummary> {
        val body = get("/actions/runs/$runId/jobs")
        return json.decodeFromString<JobsPayload>(body).jobs.map { job ->
            JobSummary(name = job.name, status = job.status, conclusion = job.conclusion)
        }
    }

    private fun get(path: String): String = call("GET", path, null, "application/vnd.github+json").toString(Charsets.UTF_8)

    private fun post(path: String, payload: String): String =
        call("POST", path, payload, "application/vnd.github+json").toString(Charsets.UTF_8)

    private fun call(method: String, path: String, body: String?, accept: String): ByteArray {
        val builder = request(path).header("Accept", accept)
        val request = when (method) {
            "POST" -> builder.post((body ?: "").toRequestBody(JSON)).build()
            else -> builder.get().build()
        }
        http.newCall(request).execute().use { response ->
            val bytes = response.body?.bytes() ?: ByteArray(0)
            if (!response.isSuccessful) {
                throw GitHubException(errorMessage(response.code, bytes.toString(Charsets.UTF_8)), response.code)
            }
            return bytes
        }
    }

    private fun request(path: String): Request.Builder {
        return Request.Builder()
            .url("${apiBase.trimEnd('/')}/repos/$owner/$repo$path")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "blog-writer")
    }

    private fun encodeBranch(branch: String): String =
        branch.split('/').joinToString("/") { java.net.URLEncoder.encode(it, Charsets.UTF_8) }

    private fun errorMessage(status: Int, body: String): String = when (status) {
        401 -> "GitHub 令牌无效"
        403 -> "GitHub 令牌没有这个仓库的权限"
        404 -> "找不到仓库或分支"
        else -> "GitHub 返回 $status ${body.take(180)}"
    }

    @Serializable
    private data class RefPayload(val `object`: ShaPayload)

    @Serializable
    private data class ShaPayload(val sha: String)

    @Serializable
    private data class CommitPayload(val sha: String, val message: String = "", val tree: ShaPayload)

    @Serializable
    private data class TreePayload(val truncated: Boolean = false, val tree: List<TreeItem> = emptyList())

    @Serializable
    private data class TreeItem(val path: String, val mode: String = "100644", val type: String, val sha: String? = null)

    @Serializable
    private data class BlobWrite(val content: String, val encoding: String)

    @Serializable
    private data class TreeWrite(
        @kotlinx.serialization.SerialName("base_tree") val baseTree: String,
        val tree: List<TreeWriteItem>,
    )

    @Serializable
    private data class TreeWriteItem(val path: String, val mode: String, val type: String, val sha: String?)

    @Serializable
    private data class CommitWrite(val message: String, val tree: String, val parents: List<String>)

    @Serializable
    private data class RefWrite(val sha: String, val force: Boolean)

    @Serializable
    private data class RunsPayload(
        @kotlinx.serialization.SerialName("workflow_runs") val workflowRuns: List<RunPayload> = emptyList(),
    )

    @Serializable
    private data class RunPayload(
        val id: Long,
        val status: String,
        val conclusion: String? = null,
        @kotlinx.serialization.SerialName("html_url") val htmlUrl: String = "",
        val name: String? = null,
    )

    @Serializable
    private data class JobsPayload(val jobs: List<JobPayload> = emptyList())

    @Serializable
    private data class JobPayload(val name: String, val status: String, val conclusion: String? = null)

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
