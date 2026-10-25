package ee.schimke.wasmo.services

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class SessionKeysTest {
  private val first = Base64.getEncoder().encodeToString(ByteArray(32) { 1 })
  private val second = Base64.getEncoder().encodeToString(ByteArray(32) { 2 })

  @Test
  fun authenticatedEncryptionBindsToInstallAndUsesFreshNonces() {
    val keys = SessionKeys.parse("one:$first")!!
    val first = keys.seal("install1", "private-refresh-token")
    val second = keys.seal("install1", "private-refresh-token")
    assertNotEquals(first, second)
    assertFalse(first.contains("private-refresh-token"))
    assertEquals("private-refresh-token", keys.open("install1", first))
    assertFails { keys.open("install2", first) }
    assertFails { keys.open("install1", first.dropLast(4) + "AAAA") }
  }

  @Test
  fun rotationCanReadOldKeyAndWritesOnlyActiveKey() {
    val old = SessionKeys.parse("old:$first")!!
    val rotating = SessionKeys.parse("new:$second,old:$first")!!
    assertEquals("value", rotating.open("install", old.seal("install", "value")))
    val updated = rotating.seal("install", "value")
    assertEquals("value", SessionKeys.parse("new:$second")!!.open("install", updated))
    assertFails { old.open("install", updated) }
  }

  @Test
  fun configurationErrorsDoNotEchoKeyMaterial() {
    for (config in listOf("secret-material", "key:secret-material", "same:$first,same:$first")) {
      val failure = assertFails { SessionKeys.parse(config) }
      assertEquals("Invalid WASMO_SESSION_KEYS configuration", failure.message)
    }
  }
}
