package eu.kanade.tachiyomi.animeextension.en.hanime

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM token crypto used by hanime.tv's `/api/v11/handshake` player API.
 *
 * The browser encrypts the handshake body and decrypts the `x-token` response
 * header with a static key derived as:
 * ```
 * AES key = SHA-256("htv-insecure-handshake-v1")
 * AAD     = "htv-insecure-v1"
 * ```
 *
 * Wire format (base64url of JSON):
 * ```
 * { "v":1, "alg":"AES-256-GCM", "iv":..., "tag":..., "data":... }
 * ```
 * where `data` is ciphertext and `tag` is the 16-byte GCM auth tag, both base64url.
 */
object HandshakeCrypto {

    private const val KEY_MATERIAL = "htv-insecure-handshake-v1"
    private const val AAD = "htv-insecure-v1"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    private val aesKey: SecretKeySpec by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
        val keyBytes = digest.digest(KEY_MATERIAL.toByteArray(Charsets.UTF_8))
        SecretKeySpec(keyBytes, "AES")
    }

    private val aadBytes: ByteArray by lazy { AAD.toByteArray(Charsets.UTF_8) }

    /**
     * Encrypt a UTF-8 plaintext payload into a base64url-wrapped token string
     * suitable for `{"token":"..."}` handshake POSTs.
     */
    fun encrypt(plaintext: String): String {
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(aadBytes)
        val ctAndTag = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val tagLength = GCM_TAG_BITS / 8
        val ciphertext = ctAndTag.copyOfRange(0, ctAndTag.size - tagLength)
        val tag = ctAndTag.copyOfRange(ctAndTag.size - tagLength, ctAndTag.size)

        // Manual JSON avoids a kotlinx dependency cycle for this tiny envelope.
        val envelope = buildString {
            append("{\"v\":1,\"alg\":\"AES-256-GCM\",\"iv\":\"")
            append(encodeBase64Url(iv))
            append("\",\"tag\":\"")
            append(encodeBase64Url(tag))
            append("\",\"data\":\"")
            append(encodeBase64Url(ciphertext))
            append("\"}")
        }
        return encodeBase64Url(envelope.toByteArray(Charsets.UTF_8))
    }

    /**
     * Decrypt a base64url-wrapped token (e.g. the `x-token` response header)
     * into UTF-8 plaintext JSON.
     */
    fun decrypt(token: String): String {
        val envelopeJson = String(decodeBase64Url(token), Charsets.UTF_8)
        val iv = decodeBase64Url(extractJsonString(envelopeJson, "iv"))
        val tag = decodeBase64Url(extractJsonString(envelopeJson, "tag"))
        val data = decodeBase64Url(extractJsonString(envelopeJson, "data"))

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(aadBytes)
        val plain = cipher.doFinal(data + tag)
        return String(plain, Charsets.UTF_8)
    }

    /** Minimal string-field extractor for the fixed envelope shape. */
    private fun extractJsonString(json: String, key: String): String {
        val marker = "\"$key\":\""
        val start = json.indexOf(marker)
        if (start < 0) throw IllegalArgumentException("Missing handshake field: $key")
        val valueStart = start + marker.length
        val valueEnd = json.indexOf('"', valueStart)
        if (valueEnd < 0) throw IllegalArgumentException("Malformed handshake field: $key")
        return json.substring(valueStart, valueEnd)
    }

    private fun encodeBase64Url(bytes: ByteArray): String = android.util.Base64.encodeToString(
        bytes,
        android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
    )

    private fun decodeBase64Url(input: String): ByteArray {
        val base64 = input.replace('-', '+').replace('_', '/')
        val padding = when (base64.length % 4) {
            2 -> "=="
            3 -> "="
            else -> ""
        }
        return android.util.Base64.decode(base64 + padding, android.util.Base64.DEFAULT)
    }
}
