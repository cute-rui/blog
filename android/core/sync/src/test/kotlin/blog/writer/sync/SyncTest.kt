package blog.writer.sync

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncTest {
    @Test
    fun gitBlobShaMatchesGit() {
        assertEquals("b6fc4c620b67d95f953a5c1c1230aaab5db5a1b0", GitBlob.sha1("hello".toByteArray()))
        assertEquals(
            "ce013625030ba8dba906f756967f9e9ca394464a",
            GitBlob.sha1("hello\n".toByteArray()),
        )
    }

    @Test
    fun pullPlanDownloadsOnlyChangedCleanFiles() {
        val unchanged = "same".toByteArray()
        val dirty = "local edit".toByteArray()
        val baseDirty = GitBlob.sha1("base".toByteArray())
        val remote = listOf(
            RemoteFile("posts/a/index.md", GitBlob.sha1(unchanged)),
            RemoteFile("posts/b/index.md", GitBlob.sha1("remote".toByteArray())),
            RemoteFile("posts/c/index.md", "remote-c"),
            RemoteFile("README.md", "ignored"),
        )
        val local = listOf(
            LocalSyncFile("posts/a/index.md", unchanged, GitBlob.sha1(unchanged)),
            LocalSyncFile("posts/b/index.md", "base".toByteArray(), GitBlob.sha1("base".toByteArray())),
            LocalSyncFile("posts/c/index.md", dirty, baseDirty),
            LocalSyncFile("posts/d/index.md", "new".toByteArray(), null),
            LocalSyncFile("posts/e/index.md", "old".toByteArray(), GitBlob.sha1("old".toByteArray())),
        )
        val actions = planPull(remote, local).associateBy { actionPath(it) }
        assertTrue(actions["posts/a/index.md"] is PullAction.InSync)
        assertTrue(actions["posts/b/index.md"] is PullAction.Download)
        assertTrue(actions["posts/c/index.md"] is PullAction.Conflict)
        assertTrue(actions["posts/d/index.md"] is PullAction.LocalOnly)
        assertTrue(actions["posts/e/index.md"] is PullAction.DeleteLocal)
        assertTrue(actions.keys.none { it == "README.md" })
    }

    @Test
    fun engineDoesNotDownloadUnchangedBlobs() = runBlocking {
        val bytes = "hello".toByteArray()
        val sha = GitBlob.sha1(bytes)
        val api = MemoryGitHub(head = "base", files = mapOf("posts/a/index.md" to bytes))
        val outcome = SyncEngine().pull(
            api,
            "main",
            listOf(LocalSyncFile("posts/a/index.md", bytes, sha)),
        )
        assertEquals(0, api.blobReads)
        assertTrue(outcome.downloads.isEmpty())
        assertEquals("base", outcome.headSha)
    }

    @Test
    fun pushRejectsWhenTheRemoteMovedAndDoesNotAdvanceTheRef() = runBlocking {
        val api = MemoryGitHub(head = "other", files = emptyMap())
        val result = SyncEngine().push(
            api,
            PushRequest(
                branch = "main",
                baseCommitSha = "base",
                message = commitMessage(),
                upserts = listOf(PushFile("posts/a/index.md", "x".toByteArray())),
                deletes = emptyList(),
            ),
        )
        assertTrue(result is PushResult.RemoteMoved)
        assertEquals("other", api.head)
        assertEquals(0, api.commitsCreated)
    }

    @Test
    fun pushFastForwardsWhenTheBaseStillMatches() = runBlocking {
        val api = MemoryGitHub(head = "base", files = emptyMap())
        val message = "更新文章\n\nPublish-To: blog,wechat\n"
        val result = SyncEngine().push(
            api,
            PushRequest(
                branch = "main",
                baseCommitSha = "base",
                message = message,
                upserts = listOf(PushFile("posts/a/index.md", "正文".toByteArray())),
                deletes = listOf("drafts/a/index.md"),
            ),
        )
        assertTrue(result is PushResult.Pushed)
        assertEquals(message, api.lastMessage)
        assertTrue(api.head != "base")
        assertEquals("正文", api.files["posts/a/index.md"]?.toString(Charsets.UTF_8))
        assertTrue("drafts/a/index.md" !in api.files)
    }

    @Test
    fun paragraphMergeKeepsNonOverlappingEditsAndSurfacesOverlaps() {
        val base = "一\n\n二\n\n三"
        val local = "一改\n\n二\n\n三"
        val remote = "一\n\n二\n\n三改"
        val clean = mergeParagraphs(base, local, remote)
        assertTrue(clean.clean)
        assertEquals("一改\n\n二\n\n三改", clean.render())
        val conflict = mergeParagraphs(base, "一甲\n\n二\n\n三", "一乙\n\n二\n\n三")
        assertTrue(conflict.pieces.any { it.conflict })
        assertEquals("一甲\n\n二\n\n三", conflict.render { true })
        assertEquals("一乙\n\n二\n\n三", conflict.render { false })
        assertTrue(mergeParagraphs(base, local, local).clean)
    }

    @Test
    fun copyGateWaitsForThePublishedHash() {
        assertTrue(WechatCopy.decide(null, "sha256:abc") is CopyDecision.Waiting)
        assertTrue(WechatCopy.decide("sha256:abc", "sha256:old") is CopyDecision.Waiting)
        assertTrue(WechatCopy.decide("sha256:abc", "sha256:abc") is CopyDecision.Ready)
        val index = WechatCopy.parseIndex(
            """{"wechatMode":"draft","posts":{"hello":"https://example.com/wx/hello-key/"}}""",
        )
        assertEquals("draft", index.wechatMode)
        assertEquals(
            "https://example.com/wx/hello-key/content.html",
            WechatCopy.contentUrl(index.pages.getValue("hello")),
        )
        val meta = WechatCopy.parseMeta("""{"title":"题","contentHash":"sha256:abc","openComment":true}""")
        assertEquals("sha256:abc", meta.contentHash)
        assertEquals(true, meta.openComment)
    }

    @Test
    fun publishProgressFollowsTheRun() {
        val running = WorkflowRunSummary(1, "in_progress", null, "https://github.com/run/1", "publish")
        val building = mapPublishProgress(running, blogSelected = true, wechatSelected = true, wechatMode = "html")
        assertEquals(ProgressPhase.Building, building.blog?.phase)
        assertEquals("构建中…", building.wechat?.label)
        assertEquals(null, building.draft)
        val done = WorkflowRunSummary(
            2,
            "completed",
            "success",
            "https://github.com/run/2",
            "publish",
            jobs = listOf(JobSummary("wechat-draft", "completed", "failure")),
        )
        val drafted = mapPublishProgress(done, blogSelected = true, wechatSelected = true, wechatMode = "draft")
        assertEquals("网站已上线", drafted.blog?.label)
        assertEquals("公众号版本已就绪", drafted.wechat?.label)
        assertEquals(ProgressPhase.Failed, drafted.draft?.phase)
        assertTrue(drafted.draft?.detail?.contains("仍可复制") == true)
    }

    @Test
    fun githubClientSendsTheTokenAndTreats422AsNotFastForward() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(422).setBody("""{"message":"not a fast forward"}"""))
            server.enqueue(
                MockResponse().setBody("""{"ref":"refs/heads/main","object":{"sha":"remote-head","type":"commit"}}"""),
            )
            val client = GitHubRestClient(
                http = OkHttpClient(),
                owner = "lightwing",
                repo = "blog-content",
                token = "ghp_test",
                apiBase = server.url("/").toString().trimEnd('/'),
            )
            val rejected = client.updateRef("main", "newsha", force = false)
            assertTrue(rejected is RefUpdate.Rejected)
            assertEquals("remote-head", (rejected as RefUpdate.Rejected).currentSha)
            val patch = server.takeRequest()
            assertEquals("PATCH", patch.method)
            assertEquals("Bearer ghp_test", patch.getHeader("Authorization"))
            assertEquals("blog-writer", patch.getHeader("User-Agent"))
            assertTrue(patch.path!!.endsWith("/git/refs/heads/main"))
            assertTrue(patch.body.readUtf8().contains("\"force\":false"))
        } finally {
            server.shutdown()
        }
    }

    private fun commitMessage() = "更新文章\n\nPublish-To: wechat\n"

    private fun actionPath(action: PullAction): String = when (action) {
        is PullAction.InSync -> action.path
        is PullAction.Download -> action.path
        is PullAction.DeleteLocal -> action.path
        is PullAction.Conflict -> action.path
        is PullAction.LocalOnly -> action.path
    }
}

private class MemoryGitHub(
    var head: String,
    files: Map<String, ByteArray>,
) : GitHubContentApi {
    val files = files.toMutableMap()
    var blobReads = 0
    var commitsCreated = 0
    var lastMessage: String? = null
    private val blobs = files.values.associateBy { GitBlob.sha1(it) }.toMutableMap()

    override suspend fun head(branch: String): String = head

    override suspend fun commit(sha: String): RemoteCommit = RemoteCommit(sha, "tree-$sha", "")

    override suspend fun tree(treeSha: String): List<RemoteFile> =
        files.map { (path, bytes) -> RemoteFile(path, GitBlob.sha1(bytes)) }

    override suspend fun readBlob(sha: String): ByteArray {
        blobReads++
        return blobs.getValue(sha)
    }

    override suspend fun createBlob(bytes: ByteArray): String {
        val sha = GitBlob.sha1(bytes)
        blobs[sha] = bytes
        return sha
    }

    override suspend fun createTree(baseTreeSha: String, changes: List<TreeEntry>): String {
        for (change in changes) {
            if (change.blobSha == null) files.remove(change.path)
            else files[change.path] = blobs.getValue(change.blobSha)
        }
        return "tree-next"
    }

    override suspend fun createCommit(parentSha: String, treeSha: String, message: String): String {
        commitsCreated++
        lastMessage = message
        return "commit-$commitsCreated"
    }

    override suspend fun updateRef(branch: String, sha: String, force: Boolean): RefUpdate {
        if (!force && head != "base") return RefUpdate.Rejected(head)
        // The engine only calls this after checking head == base. A racing move is a non-fast-forward.
        if (!force && parentMissing(sha)) return RefUpdate.Rejected(head)
        head = sha
        return RefUpdate.Updated(sha)
    }

    private fun parentMissing(sha: String): Boolean = false
}
