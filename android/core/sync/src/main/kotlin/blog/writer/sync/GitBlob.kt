package blog.writer.sync

import java.security.MessageDigest

object GitBlob {
    fun sha1(content: ByteArray): String {
        val header = "blob ${content.size}\u0000".toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(header)
        digest.update(content)
        return digest.digest().toHex()
    }
}

object ContentHash {
    fun sha256(content: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(content)
        return "sha256:" + digest.toHex()
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

object ContentPaths {
    private val extensions = setOf("md", "mdx", "png", "jpg", "jpeg", "webp", "svg", "gif")

    fun isSyncPath(path: String): Boolean {
        if (!(path.startsWith("posts/") || path.startsWith("drafts/"))) return false
        val ext = path.substringAfterLast('.', "")
        return ext.lowercase() in extensions
    }
}
