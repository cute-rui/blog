package blog.writer.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageHostTest {
    @Test
    fun uploadStoresTheImageBesideThePost() = runBlocking {
        val files = linkedMapOf<String, ByteArray>()
        val host = BesidePostImageHost(
            exists = { path -> files.containsKey(path) },
            write = { path, bytes -> files[path] = bytes },
        )
        val first = host.upload("drafts/note/index.md", "My Photo.PNG", PNG)
        val second = host.upload("drafts/note/index.md", "My Photo.PNG", PNG)
        assertEquals("./My-Photo.png", first)
        assertEquals("./My-Photo-2.png", second)
        assertTrue(files.containsKey("drafts/note/My-Photo.png"))
        assertEquals(PNG.toList(), files.getValue("drafts/note/My-Photo-2.png").toList())
    }

    @Test
    fun unsupportedBytesAreNotNamed() {
        assertNull(imageFileName("notes.txt", "hello".toByteArray()))
        assertEquals("image.jpg", imageFileName(null, JPEG))
        assertEquals("封面.svg", imageFileName("封面.svg", "<svg></svg>".toByteArray()))
    }

    @Test
    fun commitAndMoveKeepImagesWithTheArticle() {
        val local = listOf("drafts/note/index.md", "drafts/note/dot.png", "drafts/other/index.md")
        assertEquals(
            setOf("drafts/note/index.md", "drafts/note/dot.png"),
            articleCommitPaths("drafts/note/index.md", local),
        )
        assertEquals(
            listOf(
                "drafts/note/index.md" to "posts/note/index.md",
                "drafts/note/dot.png" to "posts/note/dot.png",
            ),
            movedWithArticle("drafts/note/index.md", "posts/note/index.md", local),
        )
        assertEquals("drafts/note/dot.png", resolveArticleImage("drafts/note/index.md", "./dot.png"))
        assertNull(resolveArticleImage("drafts/note/index.md", "https://example.com/dot.png"))
        assertNull(resolveArticleImage("drafts/note/index.md", "../secret.png"))
    }

    private companion object {
        val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
    }
}
