package blog.writer.data.repo
import blog.writer.copy.Copy

import blog.writer.content.FrontMatterCodec
import blog.writer.content.PostDocument
import blog.writer.content.PostFrontMatter
import blog.writer.content.countWritingCharacters
import blog.writer.content.isDraftPath
import blog.writer.content.postPath
import blog.writer.content.slugOf
import blog.writer.data.db.PendingDraftEntity
import blog.writer.data.db.PostEntity
import blog.writer.data.db.WriterDao
import blog.writer.data.files.LocalContentStore
import blog.writer.sync.ContentHash
import blog.writer.sync.ContentPaths
import blog.writer.sync.GitBlob
import blog.writer.sync.LocalSyncFile
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PostRepository @Inject constructor(
    private val files: LocalContentStore,
    private val dao: WriterDao,
) {
    fun observe(): Flow<List<PostEntity>> = dao.observePosts()

    suspend fun seedIfEmpty() {
        files.ensure()
        if (files.listContentFiles().any { it.endsWith(".md") }) {
            reindex()
            return
        }
        val matter = PostFrontMatter(
            title = Copy["seed_title"],
            description = Copy["seed_description"],
            pubDate = LocalDate.now().toString(),
            tags = listOf(Copy["app_name"]),
            draft = true,
        )
        val body = Copy["seed_body"]
        write(PostDocument(postPath("welcome", drafts = true), matter, body), dirty = true)
    }

    suspend fun reindex() {
        files.ensure()
        val pending = dao.pendingPaths().toSet()
        val seen = mutableSetOf<String>()
        for (path in files.listContentFiles()) {
            if (!path.endsWith(".md")) continue
            val text = files.read(path) ?: continue
            val document = runCatching { FrontMatterCodec.decode(path, text) }.getOrNull() ?: continue
            seen += path
            val bytes = text.toByteArray()
            val existing = dao.post(path)
            dao.upsertPost(document.toEntity(bytes, existing, pending.contains(path), touchTime = false))
        }
        for (path in dao.postPaths()) {
            if (path !in seen) dao.deletePost(path)
        }
    }

    suspend fun read(path: String): PostDocument {
        val text = files.read(path) ?: error(Copy.format("post_missing", "path" to path))
        return FrontMatterCodec.decode(path, text)
    }

    suspend fun writeBody(path: String, body: String) {
        val current = read(path)
        write(current.copy(body = body), dirty = true)
    }

    suspend fun writeArticle(path: String, matter: PostFrontMatter, body: String) {
        write(PostDocument(path, matter, body), dirty = true)
    }

    suspend fun writeMatter(path: String, matter: PostFrontMatter, newSlug: String): String {
        val current = read(path)
        val slug = newSlug.trim().ifBlank { slugOf(path) }
        val target = postPath(slug, drafts = isDraftPath(path))
        if (target != path) files.move(path, target)
        write(PostDocument(target, matter, current.body), dirty = true)
        if (target != path) {
            dao.pending(path)?.let { dao.upsertPending(it.copy(path = target)); dao.deletePending(path) }
            dao.deletePost(path)
        }
        return target
    }

    suspend fun create(): String {
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
        val slug = "note-$stamp"
        val matter = PostFrontMatter(
            title = Copy["untitled"],
            description = "",
            pubDate = LocalDate.now().toString(),
            draft = true,
        )
        val path = postPath(slug, drafts = true)
        write(PostDocument(path, matter, "# ${Copy["untitled"]}\n\n"), dirty = true)
        return path
    }

    suspend fun moveToPosts(path: String): String {
        if (!isDraftPath(path)) return path
        val target = postPath(slugOf(path), drafts = false)
        val document = read(path)
        files.move(path, target)
        write(PostDocument(target, document.frontMatter, document.body), dirty = true)
        dao.pending(path)?.let { dao.upsertPending(it.copy(path = target)); dao.deletePending(path) }
        dao.deletePost(path)
        return target
    }

    suspend fun pending(path: String): PendingDraftEntity? = dao.pending(path)

    suspend fun savePending(path: String, offset: Int, text: String) {
        dao.upsertPending(PendingDraftEntity(path, offset, text))
        dao.post(path)?.let { dao.upsertPost(it.copy(pending = true)) }
    }

    suspend fun clearPending(path: String) {
        dao.deletePending(path)
        dao.post(path)?.let { dao.upsertPost(it.copy(pending = false)) }
    }

    suspend fun markPublished(path: String, hash: String, baseSha: String?) {
        dao.post(path)?.let { dao.upsertPost(it.copy(publishedHash = hash, baseBlobSha = baseSha, dirty = false)) }
    }

    suspend fun localFiles(): List<LocalSyncFile> {
        return files.listContentFiles().filter { ContentPaths.isSyncPath(it) }.mapNotNull { path ->
            val bytes = files.readBytes(path) ?: return@mapNotNull null
            val base = if (path.endsWith(".md")) dao.post(path)?.baseBlobSha else files.readBase(path)?.let { GitBlob.sha1(it) }
            LocalSyncFile(path, bytes, base)
        }
    }

    suspend fun applyRemote(path: String, bytes: ByteArray, blobSha: String) {
        if (path.endsWith(".md") || path.endsWith(".mdx")) {
            files.write(path, bytes.toString(Charsets.UTF_8))
            files.writeBase(path, bytes)
            val document = FrontMatterCodec.decode(path, bytes.toString(Charsets.UTF_8))
            dao.upsertPost(
                document.toEntity(bytes, null, dao.pending(path) != null, touchTime = false)
                    .copy(baseBlobSha = blobSha, dirty = false),
            )
        } else {
            files.writeBytes(path, bytes)
            files.writeBase(path, bytes)
        }
    }

    suspend fun deleteLocal(path: String) {
        files.delete(path)
        dao.deletePost(path)
        dao.deletePending(path)
    }

    fun readBase(path: String): ByteArray? = files.readBase(path)

    suspend fun contentHash(path: String): String? {
        val bytes = files.readBytes(path) ?: return null
        return ContentHash.sha256(bytes)
    }

    private suspend fun write(document: PostDocument, dirty: Boolean) {
        val text = FrontMatterCodec.encode(document)
        files.write(document.path, text)
        val bytes = text.toByteArray()
        val existing = dao.post(document.path)
        val base = existing?.baseBlobSha
        val changed = base != null && GitBlob.sha1(bytes) != base
        dao.upsertPost(
            document.toEntity(bytes, existing, dao.pending(document.path) != null, touchTime = true).copy(
                dirty = dirty || changed || base == null,
                baseBlobSha = base,
            ),
        )
    }

    private fun PostDocument.toEntity(
        bytes: ByteArray,
        existing: PostEntity?,
        pending: Boolean,
        touchTime: Boolean,
    ): PostEntity {
        val sha = GitBlob.sha1(bytes)
        val base = existing?.baseBlobSha
        return PostEntity(
            path = path,
            slug = slug,
            title = frontMatter.title.ifBlank { Copy["untitled"] },
            description = frontMatter.description,
            draft = frontMatter.draft,
            publishBlog = frontMatter.publish.blog,
            publishWechat = frontMatter.publish.wechat,
            wordCount = countWritingCharacters(body),
            updatedAtEpochMs = if (touchTime) System.currentTimeMillis() else existing?.updatedAtEpochMs ?: System.currentTimeMillis(),
            dirty = base == null || sha != base,
            baseBlobSha = base,
            pending = pending,
            publishedHash = existing?.publishedHash,
        )
    }
}
