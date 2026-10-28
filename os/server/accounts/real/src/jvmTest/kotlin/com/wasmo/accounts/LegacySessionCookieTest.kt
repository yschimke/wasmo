package com.wasmo.accounts

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import com.wasmo.framework.UserAgent
import com.wasmo.identifiers.Deployment
import com.wasmo.identifiers.DistributionShortCode
import kotlin.test.Test
import kotlin.time.Clock
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.ByteString.Companion.encodeUtf8

class LegacySessionCookieTest {
  private val deployment = Deployment(
    baseUrl = "https://wasmo.example.com/".toHttpUrl(),
    sendFromEmailAddress = "noreply@example.com",
    distributionShortCode = DistributionShortCode("hl"),
  )

  @Test
  fun httpsExpiresTheScriptReadableCookie() {
    val userAgent = RecordingUserAgent(mapOf("wasmo_session" to "old"))
    authenticator(SessionCookieSpec.Https, userAgent).updateSessionCookie()

    assertThat(userAgent.set.filter { it.maxAgeSeconds == 0L }.map { it.name })
      .containsExactly("wasmo_session")
    assertThat(userAgent.set.filter { it.maxAgeSeconds > 0L }.map { it.name to it.httpOnly })
      .containsExactly("__Http-wasmo_session" to true)
  }

  @Test
  fun httpsWithoutALegacyCookieSetsNothingExtra() {
    val userAgent = RecordingUserAgent(mapOf())
    authenticator(SessionCookieSpec.Https, userAgent).updateSessionCookie()

    assertThat(userAgent.set.filter { it.maxAgeSeconds == 0L }).isEmpty()
  }

  @Test
  fun httpKeepsItsOwnCookie() {
    val userAgent = RecordingUserAgent(mapOf())
    authenticator(SessionCookieSpec.Http, userAgent).updateSessionCookie()

    assertThat(userAgent.set.map { it.name to it.maxAgeSeconds })
      .containsExactly("wasmo_session" to 34560000L)
  }

  private fun authenticator(spec: SessionCookieSpec, userAgent: UserAgent) =
    RealClientAuthenticator(
      clock = Clock.System,
      deployment = deployment,
      sessionCookieSpec = spec,
      sessionCookieEncoder = SessionCookieEncoder(CookieSecret("secret".encodeUtf8())),
      cookieClientFactory = object : CookieClient.Factory {
        override fun create(sessionCookie: SessionCookie, userAgent: String?, ip: String?) =
          error("unused")
      },
      userAgent = userAgent,
    )

  private data class SetCookie(val name: String, val httpOnly: Boolean, val maxAgeSeconds: Long)

  private class RecordingUserAgent(private val requestCookies: Map<String, String>) : UserAgent {
    val set = mutableListOf<SetCookie>()
    override val userAgent: String? = null
    override val ip = "127.0.0.1"

    override fun getCookie(name: String) = requestCookies[name]

    override fun setCookie(
      name: String,
      value: String,
      secure: Boolean,
      httpOnly: Boolean,
      domain: String,
      path: String,
      maxAgeSeconds: Long,
    ) {
      set += SetCookie(name, httpOnly, maxAgeSeconds)
    }
  }
}
