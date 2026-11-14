package blog.writer.data.files

import java.io.File

class LocalContentStore(private val root: File) {
    private val bases = File(root.parentFile, "sync-base")

    fun ensure() {
        root.mkdirs()
        bases.mkdirs()
    }

    fun read(path: String): String? = file(path).takeIf { it.isFile }?.readText()

    fun write(path: String, text: String) {
        val target = file(path)
        target.parentFile?.mkdirs()
        target.writeText(text)
    }

    fun readBytes(path: String): ByteArray? = file(path).takeIf { it.isFile }?.readBytes()

    fun writeBytes(path: String, bytes: ByteArray) {
        val target = file(path)
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
    }

    fun delete(path: String) {
        file(path).delete()
        baseFile(path).delete()
    }

    fun move(from: String, to: String) {
        val source = file(from)
        val target = file(to)
        target.parentFile?.mkdirs()
        if (source.isFile) source.renameTo(target) || source.copyTo(target, overwrite = true).let { source.delete(); true }
        val base = baseFile(from)
        if (base.isFile) {
            val moved = baseFile(to)
            moved.parentFile?.mkdirs()
            base.renameTo(moved)
        }
    }

    fun listContentFiles(): List<String> {
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .filter { it.startsWith("posts/") || it.startsWith("drafts/") }
            .toList()
    }

    fun readBase(path: String): ByteArray? = baseFile(path).takeIf { it.isFile }?.readBytes()

    fun writeBase(path: String, bytes: ByteArray) {
        val target = baseFile(path)
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
    }

    private fun file(path: String): File = File(root, path)

    private fun baseFile(path: String): File = File(bases, path)
}
