package blog.writer.crypto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.KeyGenerator
import javax.crypto.AEADBadTagException

class CredentialCipherTest {
    @Test
    fun roundTripAndRejectsADifferentKeyOrAad() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val cipher = AesGcmCredentialCipher(key)
        val payload = cipher.encrypt("sk-test-7f3c".toByteArray(), aad = "llm:1".toByteArray())
        assertTrue(payload[0] == AesGcmCredentialCipher.VERSION)
        assertEqualsText("sk-test-7f3c", cipher.decrypt(payload, aad = "llm:1".toByteArray()))
        assertFails { cipher.decrypt(payload, aad = "llm:2".toByteArray()) }
        assertFails { AesGcmCredentialCipher(other).decrypt(payload, aad = "llm:1".toByteArray()) }
        assertFalse(payload.decodeToString().contains("sk-test"))
    }

    private fun assertEqualsText(expected: String, actual: ByteArray) {
        org.junit.Assert.assertEquals(expected, actual.toString(Charsets.UTF_8))
    }

    private fun assertFails(block: () -> Unit) {
        try {
            block()
            org.junit.Assert.fail("应该失败")
        } catch (error: AEADBadTagException) {
            // expected
        }
    }
}
