package blog.writer.images

import blog.writer.sync.articleDirectory
import blog.writer.sync.imageFileName
import blog.writer.sync.uniqueFileName

/**
 * Stores the picture next to the article. Sync sends that file in the same Git commit.
 * [upload] returns a relative Markdown path such as `./photo.png`.
 */
class BesidePostImageHost(
    private val exists: (String) -> Boolean,
    private val write: (String, ByteArray) -> Unit,
) : ImageHost {
    override suspend fun upload(articlePath: String, fileName: String, bytes: ByteArray): String {
        val directory = articleDirectory(articlePath) ?: throw IllegalArgumentException(articlePath)
        val leaf = imageFileName(fileName, bytes) ?: throw IllegalArgumentException(fileName)
        val unique = uniqueFileName(directory, leaf, exists)
        write("$directory/$unique", bytes)
        return "./$unique"
    }
}
