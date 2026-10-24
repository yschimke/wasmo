package com.wasmo.identifiers

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import kotlin.test.Test
import okhttp3.HttpUrl.Companion.toHttpUrl

class HostnamePatternsTest {
  private fun deployment(baseUrl: String) =
    Deployment(
      baseUrl = baseUrl.toHttpUrl(),
      sendFromEmailAddress = "noreply@example.com",
      distributionShortCode = DistributionShortCode("hl"),
    ).hostnamePatterns()

  @Test
  fun subdomainBaseUrlAnchorsOnTheFullHost() {
    // The bug: a subdomain base URL used to anchor on the eTLD+1 (coo.ee), so computer/app hosts
    // never matched and every OS RPC 404'd.
    val patterns = deployment("https://wasmo.coo.ee/")

    assertThat(patterns.osHostname.matches("wasmo.coo.ee")).isTrue()
    assertThat(patterns.osHostname.matches("coo.ee")).isFalse()

    assertThat(patterns.computerRegex.matches("jesse99.wasmo.coo.ee")).isTrue()
    assertThat(patterns.computerRegex.matches("jesse99.coo.ee")).isFalse()

    assertThat(patterns.appRegex.matches("journal-jesse99.wasmo.coo.ee")).isTrue()
    assertThat(patterns.appRegex.matches("journal-jesse99.coo.ee")).isFalse()
  }

  @Test
  fun registeredDomainBaseUrlIsUnchanged() {
    val patterns = deployment("https://wasmo.com/")

    assertThat(patterns.osHostname.matches("wasmo.com")).isTrue()
    assertThat(patterns.computerRegex.matches("jesse99.wasmo.com")).isTrue()
    assertThat(patterns.appRegex.matches("journal-jesse99.wasmo.com")).isTrue()
  }

  @Test
  fun multiLabelPublicSuffixBaseUrlAnchorsOnTheFullHost() {
    // A base URL under a multi-label public suffix (.co.uk) — eTLD+1 would be example.co.uk.
    val patterns = deployment("https://wasmo.example.co.uk/")

    assertThat(patterns.osHostname.matches("wasmo.example.co.uk")).isTrue()
    assertThat(patterns.osHostname.matches("example.co.uk")).isFalse()
    assertThat(patterns.computerRegex.matches("jesse99.wasmo.example.co.uk")).isTrue()
  }
}
