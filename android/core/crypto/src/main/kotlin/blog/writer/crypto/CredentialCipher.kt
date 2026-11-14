package blog.writer.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM payload used for API keys.
 *
 * The Android app supplies a key created in the Android Keystore. Tests supply an ordinary
 * AES key. The wire format is the same either way: one version byte, a 12-byte IV, then the
 * ciphertext with its GCM tag. This is not EncryptedSharedPreferences.
 */
interface CredentialCipher {
    fun encrypt(plaintext: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray

    fun decrypt(payload: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray
}

class AesGcmCredentialCipher(
    private val key: SecretKey,
    private val random: SecureRandom = SecureRandom(),
) : CredentialCipher {
    override fun encrypt(plaintext: ByteArray, aad: ByteArray): ByteArray {
        val iv = ByteArray(IV_BYTES)
        random.nextBytes(iv)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        val encrypted = cipher.doFinal(plaintext)
        return ByteArray(1 + iv.size + encrypted.size).also { out ->
            out[0] = VERSION
            iv.copyInto(out, destinationOffset = 1)
            encrypted.copyInto(out, destinationOffset = 1 + iv.size)
        }
    }

    override fun decrypt(payload: ByteArray, aad: ByteArray): ByteArray {
        require(payload.size > 1 + IV_BYTES) { "凭据数据太短" }
        require(payload[0] == VERSION) { "不认识的凭据版本" }
        val iv = payload.copyOfRange(1, 1 + IV_BYTES)
        val encrypted = payload.copyOfRange(1 + IV_BYTES, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(encrypted)
    }

    companion object {
        const val VERSION: Byte = 1
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
