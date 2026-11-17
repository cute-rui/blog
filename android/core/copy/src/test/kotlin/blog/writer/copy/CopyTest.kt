package blog.writer.copy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CopyTest {
    @Test
    fun catalogLoadsAndKeysAreSnakeCase() {
        val catalog = Copy.parse(readCatalog())
        assertTrue(catalog.isNotEmpty())
        catalog.forEach { (key, value) ->
            assertTrue("key $key", key.matches(Regex("[a-z][a-z0-9_]*")))
            assertTrue("empty $key", value.isNotEmpty())
        }
    }

    @Test
    fun everyLookupExistsInTheCatalog() {
        val catalog = Copy.parse(readCatalog())
        val lookup = Regex("Copy\\[\"([a-z][a-z0-9_]*)\"")
        val format = Regex("Copy\\.format\\(\"([a-z][a-z0-9_]*)\"")
        val missing = linkedSetOf<String>()
        androidRoot().walkTopDown().filter { it.extension == "kt" && "src/main" in it.path }.forEach { file ->
            val text = file.readText()
            (lookup.findAll(text) + format.findAll(text)).forEach { match ->
                val key = match.groupValues[1]
                if (key !in catalog) missing += "${file.name}:$key"
            }
        }
        assertTrue(missing.joinToString(), missing.isEmpty())
    }

    @Test
    fun launcherNameMatchesTheCatalog() {
        val catalog = Copy.parse(readCatalog())
        val xml = androidRoot().resolve("app/src/main/res/values/strings.xml").readText()
        val name = Regex("<string name=\"app_name\">(.*?)</string>").find(xml)!!.groupValues[1]
        assertEquals(catalog.getValue("app_name"), name)
    }

    @Test
    fun userVisibleChineseLivesInTheCatalog() {
        val allowFiles = setOf("Prompts.kt", "ContextAssembler.kt", "ConnectionTester.kt")
        val allowLiterals = setOf("昨天", "搜索", "检索", "(\\d+)\\s*天前")
        val literal = Regex("""("(?:\\.|[^"\\])*")""")
        val leftovers = mutableListOf<String>()
        androidRoot().walkTopDown().filter { file ->
            file.extension == "kt" && "src/main" in file.path && file.name !in allowFiles
        }.forEach { file ->
            val code = file.readText().replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            code.lineSequence().forEachIndexed { index, line ->
                val source = line.substringBefore("//")
                literal.findAll(source).forEach { match ->
                    val value = runCatching { jsonString(match.groupValues[1]) }.getOrNull() ?: return@forEach
                    if (value.any { it in '\u4e00'..'\u9fff' } && value !in allowLiterals) {
                        leftovers += "${file.name}:${index + 1}:$value"
                    }
                }
            }
        }
        assertTrue(leftovers.joinToString("\n"), leftovers.isEmpty())
    }

    private fun readCatalog(): String = Copy::class.java.classLoader.getResource("app-copy.json")!!.readText()

    private fun androidRoot(): File {
        var dir = File("").absoluteFile
        while (dir.parentFile != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return dir
    }

    private fun jsonString(token: String): String = Copy.parse("{\"v\":$token}").getValue("v")
}
