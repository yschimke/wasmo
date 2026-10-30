package ee.schimke.wasmo.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.encodeUtf8
import wasmo.app.FakePlatform
import wasmo.app.WebProxy
import wasmo.http.FakeHttpService
import wasmo.http.HttpRequest
import wasmo.http.HttpResponse
import wasmo.sql.FakeSqlService
import wit.wasmo.http.v0_1_0.Header

class WebProxyServiceTest {
  private val podcasts =
    WebProxy(
      name = "podcasts",
      origin = "https://api.example.com",
      requestHeaders = listOf("Authorization"),
      routes = listOf(WebProxy.Route("POST", "/up_next/list"), WebProxy.Route("GET", "/feeds/**")),
    )
  private val unapproved =
    WebProxy("other", "https://other.example.com", listOf(), listOf(WebProxy.Route("GET", "/**")))

  private class Fixture(val platform: FakePlatform) {
    val sent = mutableListOf<wit.wasmo.http.v0_1_0.HttpRequest>()

    init {
      platform.publicHttpsService =
        FakeHttpService().apply {
          this += FakeHttpService.Handler { request ->
            sent += request
            HttpResponse(
              code = 200U,
              headers =
                listOf(
                  Header("Content-Type", "text/html"),
                  Header("Set-Cookie", "upstream=1"),
                  Header("Location", "https://elsewhere.example/"),
                ),
              body = "<script>alert(1)</script>".encodeUtf8(),
            )
          }
        }
    }

    suspend fun request(
      path: String,
      method: String = "GET",
      headers: List<Header> = listOf(),
      body: String? = null,
    ) =
      AppServicesApp.Factory(proxyOrigins = setOf("https://api.example.com"))
        .create(platform)
        .httpService
        .execute(
          HttpRequest(
            method = method,
            url = "http://internal-untrusted-host/api/wasmo/v1/$path",
            headers = headers + Header("X-Wasmo-Api", "1"),
            body = body?.encodeUtf8(),
          )
        )
  }

  private suspend fun fixture(block: suspend (Fixture) -> Unit) {
    FakeSqlService("wasmo_web_proxy_test").use { sql ->
      block(Fixture(FakePlatform(sql).apply { proxies = listOf(podcasts, unapproved) }))
    }
  }

  @Test
  fun declaredRoutesForwardOnlyDeclaredHeaders() = runTest {
    fixture { f ->
      assertTrue(
        f.request("capabilities").body.utf8().contains("\"proxies\":{\"podcasts\":true,\"other\":false}")
      )
      val response =
        f.request(
          "proxy/podcasts/up_next/list?page=2",
          "POST",
          listOf(
            Header("Content-Type", "application/json"),
            Header("Authorization", "Bearer app-held"),
            Header("Cookie", "wasmo-session=owner"),
            Header("X-Unlisted", "1"),
          ),
          """{"version":2}""",
        )
      assertEquals(200U, response.code)
      val sent = f.sent.single()
      assertEquals("https://api.example.com/up_next/list?page=2", sent.url.value)
      assertEquals("POST", sent.method)
      assertEquals("""{"version":2}""", sent.body!!.utf8())
      assertEquals(
        listOf("Content-Type", "Authorization"),
        sent.headers.map { it.name },
      )
      // Upstream cookies and redirects don't reach the app's origin, and the body can't run there.
      val names = response.headers.map { it.name.lowercase() }
      assertTrue("set-cookie" !in names && "location" !in names)
      assertEquals(
        "sandbox; default-src 'none'",
        response.headers.single { it.name == "Content-Security-Policy" }.value,
      )

      assertEquals(200U, f.request("proxy/podcasts/feeds").code)
      assertEquals(200U, f.request("proxy/podcasts/feeds/123/episodes").code)
      assertEquals(null, f.sent.last().body)
    }
  }

  @Test
  fun undeclaredOrUnapprovedRequestsNeverLeave() = runTest {
    fixture { f ->
      val refused =
        mapOf(
          "proxy/missing/x" to 404U,
          "proxy/other/x" to 403U,
          "proxy/podcasts/up_next/list" to 403U, // declared for POST only
          "proxy/podcasts/feedsx" to 403U,
          "proxy/podcasts/feeds/../user/token" to 400U,
          "proxy/podcasts/feeds/%2e%2e/user" to 400U,
          "proxy/podcasts/feeds/a%2Fb" to 400U,
          "proxy/podcasts/feeds//x" to 400U,
          "proxy/podcasts" to 403U,
        )
      for ((path, status) in refused) assertEquals(status, f.request(path).code, path)
      assertEquals(403U, f.request("proxy/podcasts/feeds/1", "DELETE").code)
      assertEquals(0, f.sent.size)
    }
  }

  @Test
  fun mutationsStillNeedTheCsrfHeader() = runTest {
    fixture { f ->
      val response =
        AppServicesApp.Factory(proxyOrigins = setOf("https://api.example.com"))
          .create(f.platform)
          .httpService
          .execute(
            HttpRequest(
              method = "POST",
              url = "http://internal-untrusted-host/api/wasmo/v1/proxy/podcasts/up_next/list",
              body = "{}".encodeUtf8(),
            )
          )
      assertEquals(403U, response.code)
      assertEquals(0, f.sent.size)
    }
  }

  @Test
  fun operatorApprovalsParse() {
    assertEquals(
      mapOf(
        "cadence" to setOf("https://api.pocketcasts.com", "https://feeds.example.com:8443"),
        "terrazzo" to setOf("https://api.example.com"),
      ),
      appProxies(
        " cadence=https://api.pocketcasts.com, terrazzo=https://api.example.com,cadence=https://feeds.example.com:8443"
      ),
    )
    assertEquals(mapOf(), appProxies(null))
    assertFailsWith<IllegalArgumentException> { appProxies("cadence=http://api.example.com") }
    assertFailsWith<IllegalArgumentException> { appProxies("cadence=https://api.example.com/v1") }
    assertFailsWith<IllegalArgumentException> { appProxies("Cadence=https://api.example.com") }
  }
}
