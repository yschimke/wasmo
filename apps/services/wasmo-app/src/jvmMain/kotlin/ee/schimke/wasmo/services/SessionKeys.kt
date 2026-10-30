package ee.schimke.wasmo.services

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** First key encrypts, all configured keys decrypt, allowing a staged lazy rotation. */
class SessionKeys private constructor(private val keys: Map<String, SecretKeySpec>) {
  internal fun seal(context: String, value: String, purpose: String = HA_PURPOSE): String {
    val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
    val (id, key) = keys.entries.first()
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
    cipher.updateAAD("$purpose\u0000$context".toByteArray(Charsets.UTF_8))
    val bytes = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
    return "$id.${Base64.getEncoder().encodeToString(nonce)}.${Base64.getEncoder().encodeToString(bytes)}"
  }

  /** True if [value] was sealed with the key [seal] uses now. */
  internal fun isActive(value: String): Boolean = value.substringBefore('.') == keys.keys.first()

  internal fun open(context: String, value: String, purpose: String = HA_PURPOSE): String {
    val parts = value.split('.')
    require(parts.size == 3)
    val key = requireNotNull(keys[parts[0]])
    val nonce = Base64.getDecoder().decode(parts[1])
    require(nonce.size == 12)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
    cipher.updateAAD("$purpose\u0000$context".toByteArray(Charsets.UTF_8))
    return cipher.doFinal(Base64.getDecoder().decode(parts[2])).toString(Charsets.UTF_8)
  }

  companion object {
    // Each custody kind seals under its own purpose, so one ciphertext can't stand in for another.
    private const val HA_PURPOSE = "wasmo-ha-session-v1"

    fun parse(value: String?): SessionKeys? {
      if (value.isNullOrBlank()) return null
      // Configuration errors never include the operator's value in the exception.
      try {
        val keys = linkedMapOf<String, SecretKeySpec>()
        for (entry in value.split(',')) {
          val parts = entry.trim().split(':', limit = 2)
          require(parts.size == 2 && Regex("[a-zA-Z0-9_-]{1,32}").matches(parts[0]))
          val bytes = Base64.getDecoder().decode(parts[1])
          require(bytes.size == 32 && !keys.containsKey(parts[0]))
          keys[parts[0]] = SecretKeySpec(bytes, "AES")
        }
        return SessionKeys(keys)
      } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("Invalid WASMO_SESSION_KEYS configuration")
      }
    }
  }
}
