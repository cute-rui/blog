package blog.writer.images

/**
 * Where an inserted picture is stored.
 *
 * [upload] returns the path or URL the editor writes into the Markdown image.
 * The app binds one implementation. This type has no credentials.
 */
interface ImageHost {
    suspend fun upload(articlePath: String, fileName: String, bytes: ByteArray): String
}
