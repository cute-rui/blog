package blog.writer.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WechatBodyTest {
    @Test
    fun identicalTextHasNoOverrides() {
        assertEquals(emptyList<BodyOverride>(), diffWechatBody("甲\n", "甲\n"))
    }

    @Test
    fun smallEditsRoundTripThroughFindAndReplace() {
        listOf(
            "你好，博客。\n" to "你好，公众号。\n",
            "甲\n\n乙\n" to "甲\n",
            "甲\n\n丙\n" to "甲\n\n乙\n\n丙\n",
            "甲\n" to "乙\n甲\n",
            "甲\n" to "甲\n乙\n",
            "甲\n\n乙\n\n丙\n" to "甲\n\n乙乙\n\n丙丙\n",
            "同一句。\n\n同一句。\n" to "同一句。\n\n另一句。\n",
        ).forEach { (shared, edited) ->
            val overrides = diffWechatBody(shared, edited)
            val applied = applyWechatBody(shared, overrides)
            assertEquals(edited, applied.text)
            assertTrue(applied.stale.isEmpty())
        }
    }

    @Test
    fun emptySharedBodyBecomesTheWechatText() {
        val overrides = diffWechatBody("", "只有公众号")
        assertEquals(listOf(BodyOverride("", "只有公众号")), overrides)
        assertEquals("只有公众号", applyWechatBody("", overrides).text)
    }

    @Test
    fun aMissingFindStaysInTheSharedBody() {
        val applied = applyWechatBody("甲", listOf(BodyOverride("不存在", "乙")))
        assertEquals("甲", applied.text)
        assertEquals(1, applied.stale.size)
    }
}
