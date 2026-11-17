package blog.writer.data.repo

import blog.writer.crypto.CredentialCipher
import blog.writer.data.db.SecretEntity
import blog.writer.data.db.WriterDao
import blog.writer.egress.normalizeSrvDomain
import blog.writer.egress.normalizeToken
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SRV domain and egress bearer token. Both are encrypted with the Keystore
 * AES-GCM key. AppID and AppSecret are not stored.
 */
@Singleton
class EgressSettingsRepository @Inject constructor(
    private val dao: WriterDao,
    private val cipher: CredentialCipher,
) {
    suspend fun domain(): String? = read(DOMAIN)

    suspend fun token(): String? = read(TOKEN)

    suspend fun tokenLast4(): String? = token()?.takeLast(4)

    suspend fun save(domain: String, newToken: String?) {
        val normalized = normalizeSrvDomain(domain)
        val token = newToken?.takeIf { it.isNotBlank() }?.let { normalizeToken(it) }
        write(DOMAIN, normalized)
        if (token != null) write(TOKEN, token)
    }

    private suspend fun read(id: String): String? {
        val stored = dao.secret(id) ?: return null
        return cipher.decrypt(Base64.getDecoder().decode(stored.payloadBase64), aad = id.toByteArray())
            .toString(Charsets.UTF_8)
    }

    private suspend fun write(id: String, value: String) {
        val payload = cipher.encrypt(value.toByteArray(), aad = id.toByteArray())
        dao.upsertSecret(SecretEntity(id, Base64.getEncoder().encodeToString(payload)))
    }

    private companion object {
        const val DOMAIN = "egress-domain"
        const val TOKEN = "egress-token"
    }
}
