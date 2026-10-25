package com.wasmo.http

import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.IOException
import wasmo.http.HttpRequest

class PublicHttpsHttpServiceTest {
  @Test
  fun blocksPrivateReservedMetadataAndTransitionAddresses() {
    for (value in
      listOf(
        "0.0.0.0",
        "10.1.2.3",
        "127.0.0.1",
        "169.254.169.254",
        "172.16.0.1",
        "192.168.1.1",
        "100.64.0.1",
        "192.0.0.8",
        "192.0.2.1",
        "198.18.0.1",
        "198.51.100.1",
        "203.0.113.1",
        "224.0.0.1",
        "255.255.255.255",
        "168.63.129.16",
        "::1",
        "::",
        "::ffff:127.0.0.1",
        "fc00::1",
        "fe80::1",
        "fec0::1",
        "ff02::1",
        "2001:db8::1",
        "2001::1",
        "2002:7f00:1::",
        "64:ff9b::7f00:1",
        "3fff::1",
      )) {
      assertFalse(isPublicAddress(InetAddress.getByName(value)), value)
    }
    for (value in
      listOf(
        "8.8.8.8",
        "1.1.1.1",
        "93.184.216.34",
        "2606:4700:4700::1111",
        "2001:4860:4860::8888",
      )) {
      assertTrue(isPublicAddress(InetAddress.getByName(value)), value)
    }
  }

  @Test
  fun checksEveryResolutionAndRejectsMixedAnswers() {
    var answer = listOf(InetAddress.getByName("8.8.8.8"))
    val dns = PublicInternetDns(Dns { answer })
    assertEquals(answer, dns.lookup("ha.example.test"))
    answer = listOf(InetAddress.getByName("127.0.0.1"))
    assertFailsWith<UnknownHostException> { dns.lookup("ha.example.test") }
    answer = listOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("10.0.0.1"))
    assertFailsWith<UnknownHostException> { dns.lookup("ha.example.test") }
  }

  @Test
  fun hasNoRedirectProxyOrUnboundedCalls() {
    val client = publicHttpsClient()
    assertFalse(client.followRedirects)
    assertFalse(client.followSslRedirects)
    assertFalse(client.retryOnConnectionFailure)
    assertEquals(java.net.Proxy.NO_PROXY, client.proxy)
    assertTrue(client.callTimeoutMillis in 1..10_000)
  }

  @Test
  fun rejectsUnsafeUrlAndLiteralBeforeNetwork() = runBlocking {
    val http = PublicHttpsHttpService()
    for (url in
      listOf(
        "http://example.test/",
        "https://127.1/",
        "https://[::1]/",
        "https://user:pass@example.test/",
      )) {
      assertFailsWith<IllegalArgumentException> { http.execute(HttpRequest(url = url)) }
    }
  }

  @Test
  fun boundsEvenUnknownLengthResponseBodies() {
    fun response(text: String) =
      Response.Builder()
        .request(Request.Builder().url("https://example.test/").build())
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body(text.toResponseBody())
        .build()
    response("small").use { assertEquals("small", it.toWasmo(10).body.utf8()) }
    response("x".repeat(11)).use { assertFailsWith<IOException> { it.toWasmo(10) } }
  }
}
