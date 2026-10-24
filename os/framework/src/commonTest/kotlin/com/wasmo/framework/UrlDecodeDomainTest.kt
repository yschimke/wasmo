package com.wasmo.framework

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlin.test.Test

/**
 * The root-domain derivation behind the custom-domain fixes. Runs on both platform [decodeUrl]
 * actuals (okhttp on the JVM, the browser URL on JS), which is where the two bugs lived: a
 * deployment on a domain outside the built-in three had every subdomain host decode to `null`.
 */
class UrlDecodeDomainTest {
  @Test
  fun builtInRootsStillDecodeTheirSubdomain() {
    val url = "https://jesse99.wasmo.com/".decodeUrl()
    assertThat(url.topPrivateDomain).isEqualTo("wasmo.com")
    assertThat(url.subdomain).isEqualTo("jesse99")
  }

  @Test
  fun registeredRootDecodesComputerAndAppHosts() {
    registerTopPrivateDomain("wasmo.coo.ee")

    val root = "https://wasmo.coo.ee/".decodeUrl()
    assertThat(root.topPrivateDomain).isEqualTo("wasmo.coo.ee")
    assertThat(root.subdomain).isNull()

    val computer = "https://kodu.wasmo.coo.ee/".decodeUrl()
    assertThat(computer.topPrivateDomain).isEqualTo("wasmo.coo.ee")
    assertThat(computer.subdomain).isEqualTo("kodu")

    val app = "https://terrazzo-kodu.wasmo.coo.ee/".decodeUrl()
    assertThat(app.topPrivateDomain).isEqualTo("wasmo.coo.ee")
    assertThat(app.subdomain).isEqualTo("terrazzo-kodu")
  }

  @Test
  fun registeredMultiLabelPublicSuffixRootDecodesSubdomain() {
    registerTopPrivateDomain("wasmo.example.co.uk")

    val computer = "https://kodu.wasmo.example.co.uk/".decodeUrl()
    assertThat(computer.topPrivateDomain).isEqualTo("wasmo.example.co.uk")
    assertThat(computer.subdomain).isEqualTo("kodu")
  }
}
