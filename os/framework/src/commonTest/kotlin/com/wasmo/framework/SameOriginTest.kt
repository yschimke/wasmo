package com.wasmo.framework

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import kotlin.test.Test

class SameOriginTest {
  private val computerUrl = Url(
    scheme = "https",
    topPrivateDomain = "wasmo.com",
    subdomain = "jesse99",
    path = listOf("install-app"),
    query = listOf(QueryParameter("q", "1")),
  )

  @Test
  fun originDropsPathAndQuery() {
    assertThat(computerUrl.origin()).isEqualTo("https://jesse99.wasmo.com")
  }

  @Test
  fun originKeepsNonDefaultPort() {
    val local = Url(scheme = "http", topPrivateDomain = "wasmo.localhost", port = 8080)
    assertThat(local.origin()).isEqualTo("http://wasmo.localhost:8080")
  }

  @Test
  fun sameOriginIsAccepted() {
    requireSameOrigin(computerUrl, "https://jesse99.wasmo.com")
  }

  @Test
  fun siblingAppOriginIsRejected() {
    assertFailure { requireSameOrigin(computerUrl, "https://snake-jesse99.wasmo.com") }
      .isInstanceOf<ArgumentUserException>()
  }

  @Test
  fun missingOriginIsRejected() {
    assertFailure { requireSameOrigin(computerUrl, null) }
      .isInstanceOf<ArgumentUserException>()
  }
}
