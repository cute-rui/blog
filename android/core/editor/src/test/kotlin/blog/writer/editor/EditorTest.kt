package blog.writer.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorTest {
    @Test
    fun syntaxMarksHeadingsCodeAndLinksWithoutCrossingLines() {
        val text = "# 标题\n正文 `code` 和 **粗** [站](https://example.com)\n"
        val spans = MarkdownSyntax.spans(text)
        assertTrue(spans.any { it.kind == MarkdownKind.Heading && text.substring(it.start, it.end) == "标题" })
        assertTrue(spans.any { it.kind == MarkdownKind.Code && text.substring(it.start, it.end) == "`code`" })
        assertTrue(spans.any { it.kind == MarkdownKind.Bold })
        assertTrue(spans.any { it.kind == MarkdownKind.LinkText && text.substring(it.start, it.end) == "站" })
        assertTrue(spans.none { text.substring(it.start, it.end).contains('\n') })
    }

    @Test
    fun outlineSkipsFencesAndFindsTheCurrentSection() {
        val text = "# 一\n\n```\n# 不是标题\n```\n\n## 二\n这里"
        val headings = outline(text)
        assertEquals(listOf("一", "二"), headings.map { it.text })
        assertEquals("二", sectionAt(headings, text.length)?.text)
        val sample = "甲\n\n乙\n\n丙"
        assertEquals(2, paragraphIndex(sample, sample.indexOf("乙")))
        assertEquals(3, paragraphIndex(sample, sample.indexOf("丙")))
    }

    @Test
    fun pendingDraftStaysOutOfTheAcceptedTextUntilAccepted() {
        val (display, span) = PendingDrafts.insert("前文。后文。", 3, "草稿")
        assertEquals("前文。草稿后文。", display)
        assertEquals("前文。后文。", PendingDrafts.acceptedText(display, span))
        assertEquals("前文。后文。", PendingDrafts.reject(display, span))
        assertNull(PendingDrafts.adjust(display, "前文。后文。", span))
    }

    @Test
    fun editsInsideAndBeforeTheDraftMoveTheSpan() {
        val original = "前文。草稿后文。"
        val span = PendingSpan(3, 5)
        val grown = PendingDrafts.adjust(original, "前文。草稿啊后文。", span)
        assertEquals(PendingSpan(3, 6), grown)
        val shifted = PendingDrafts.adjust(original, "更前文。草稿后文。", span)
        assertEquals(PendingSpan(4, 6), shifted)
        val untouched = PendingDrafts.adjust(original, "前文。草稿后文。尾", span)
        assertEquals(span, untouched)
    }

    @Test
    fun insertionPadsParagraphBreaks() {
        val sample = PendingDrafts.prepareInsertion("前文。后文", 3, " 示范 ")
        assertTrue(sample.startsWith("\n\n"))
        assertTrue(sample.contains("示范"))
    }

    @Test
    fun imageShortcutInsertsALocalMarkdownImage() {
        val edit = MarkdownEdit.insertImage("正文", 2, "./dot.png")
        assertEquals("正文\n\n![图片](./dot.png)\n", edit.text)
        assertEquals(listOf("./dot.png"), MarkdownImages.localDestinations(edit.text + "\n![外](https://example.com/a.png)"))
    }

    @Test
    fun shortcutWrapsTheSelection() {
        val edit = MarkdownEdit.wrapSelection("你好", 0, 2, "**")
        assertEquals("**你好**", edit.text)
        assertEquals(6, edit.cursor)
    }
}
