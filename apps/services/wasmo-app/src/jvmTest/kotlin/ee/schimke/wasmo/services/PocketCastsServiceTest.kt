package ee.schimke.wasmo.services

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.ByteString.Companion.encodeUtf8
import wasmo.app.FakePlatform
import wasmo.http.FakeHttpService
import wasmo.http.HttpRequest
import wasmo.http.HttpResponse
import wasmo.sql.FakeSqlService
import wasmo.sql.SqlDatabase
import wasmo.sql.SqlService
import wit.wasmo.http.v0_1_0.Header

class PocketCastsServiceTest {
  private val signIn = """{"email":"owner@example.test","password":"private-fixture-password"}"""

  private class Fixture(sql: SqlService) {
    val platform =
      FakePlatform(
        object : SqlService {
          override suspend fun getOrCreate(name: String): SqlDatabase {
            val shared = sql.getOrCreate(name)
            return object : SqlDatabase by shared {
              override fun close() {}
            }
          }

          override fun close() {}
        }
      )
    val sent = mutableListOf<wit.wasmo.http.v0_1_0.HttpRequest>()
    var tokens = 0
    var loginStatus = 200U
    var refreshStatus = 200U
    var callStatus = 200U
    // Rejects the first call with each new access token, to exercise refresh-and-retry.
    var rejectOnce = false

    init {
      platform.publicHttpsService =
        FakeHttpService().apply {
          this += FakeHttpService.Handler { request ->
            sent += request
            val path = request.url.value.removePrefix("https://api.pocketcasts.com")
            when (path) {
              "/user/login_pocket_casts" ->
                HttpResponse(code = loginStatus, body = token("refresh-$tokens").encodeUtf8())
              "/user/token" -> HttpResponse(code = refreshStatus, body = token(null).encodeUtf8())
              else -> {
                if (rejectOnce) {
                  rejectOnce = false
                  HttpResponse(code = 401U)
                } else
                  HttpResponse(
                    code = callStatus,
                    body = """{"path":"$path","method":"${request.method}"}""".encodeUtf8(),
                  )
              }
            }
          }
        }
    }

    // Each sign-in or refresh issues a new private access token; refreshes don't rotate the
    // refresh token, as observed.
    private fun token(refresh: String?): String {
      tokens++
      val access = "\"accessToken\":\"private-fixture-access-$tokens\",\"expiresIn\":3600"
      return if (refresh == null) "{$access}"
      else "{$access,\"refreshToken\":\"private-fixture-$refresh\",\"email\":\"owner@example.test\"}"
    }

    suspend fun request(path: String, method: String = "GET", body: String? = null) =
      AppServicesApp.Factory(
          SessionKeys.parse("test:" + Base64.getEncoder().encodeToString(ByteArray(32) { 3 }))
        )
        .create(platform)
        .httpService
        .execute(
          HttpRequest(
            method = method,
            url = "http://internal-untrusted-host/api/wasmo/v1/$path",
            headers =
              listOf(Header("Content-Type", "application/json"), Header("X-Wasmo-Api", "1")),
            body = body?.encodeUtf8(),
          )
        )

    suspend fun call(path: String, body: String = "{}") =
      request("pocketcasts/call", "POST", """{"path":"$path","body":$body}""")

    fun bearer(request: wit.wasmo.http.v0_1_0.HttpRequest) =
      request.headers.firstOrNull { it.name == "Authorization" }?.value
  }

  private suspend fun fixture(block: suspend (Fixture) -> Unit) {
    FakeSqlService("wasmo_pocketcasts_test").use { block(Fixture(it)) }
  }

  @Test
  fun signInKeepsOnlySealedTokensAndCallsCarryTheBearer() = runTest {
    fixture { f ->
      assertTrue(f.request("capabilities").body.utf8().contains("\"pocketCasts\":true"))
      val signedIn = f.request("pocketcasts/session", "PUT", signIn)
      assertEquals(200U, signedIn.code)
      assertFalse(signedIn.body.utf8().contains("private-fixture"))
      val status = Json.parseToJsonElement(f.request("pocketcasts/session").body.utf8()).jsonObject
      assertEquals("true", status["signedIn"]!!.jsonPrimitive.content)
      assertEquals("owner@example.test", status["email"]!!.jsonPrimitive.content)
      val login = f.sent.single()
      assertTrue(login.body!!.utf8().contains("\"scope\":\"webplayer\""))

      val response = f.call("/up_next/list", """{"version":2}""")
      assertEquals(200U, response.code)
      assertEquals("""{"path":"/up_next/list","method":"POST"}""", response.body.utf8())
      assertEquals("Bearer private-fixture-access-1", f.bearer(f.sent.last()))
      assertEquals("""{"version":2}""", f.sent.last().body!!.utf8())
      assertEquals(200U, f.call("/user/playlists").code)
      assertEquals("GET", f.sent.last().method)
      assertEquals(null, f.sent.last().body)

      assertEquals("{}", f.request("kv").body.utf8())
      f.platform.sqlService.getOrCreate().newConnection().use { connection ->
        val sealed =
          connection.executeQuery("SELECT sealed FROM wasmo_services_pocketcasts").use {
            it.next()!!.getString(0)!!
          }
        assertFalse(sealed.contains("private-fixture"))
        assertFalse(sealed.contains("owner@example"))
      }
    }
  }

  @Test
  fun onlyTheFixedReadPathsAreForwarded() = runTest {
    fixture { f ->
      f.request("pocketcasts/session", "PUT", signIn)
      for (path in
        listOf("/user/token", "/user/login_pocket_casts", "/sync/update", "//evil.example/x", "")) {
        assertEquals(400U, f.call(path).code, path)
      }
      assertEquals(400U, f.call("/user/episode", "[1]").code)
      assertEquals(1, f.sent.size)
      assertTrue(f.sent.all { it.url.value.startsWith("https://api.pocketcasts.com/") })
    }
  }

  @Test
  fun expiredOrRejectedAccessTokensRefreshOnce() = runTest {
    fixture { f ->
      f.request("pocketcasts/session", "PUT", signIn)
      f.platform.clock.now += 1.hours
      assertEquals(200U, f.call("/user/in_progress").code)
      assertEquals("Bearer private-fixture-access-2", f.bearer(f.sent.last()))
      assertTrue(f.sent[1].body!!.utf8().contains("private-fixture-refresh-0"))

      f.rejectOnce = true
      assertEquals(200U, f.call("/user/in_progress").code)
      assertEquals("Bearer private-fixture-access-3", f.bearer(f.sent.last()))
      // The refreshed token was saved: the next call doesn't refresh again.
      val before = f.sent.size
      assertEquals(200U, f.call("/user/in_progress").code)
      assertEquals(before + 1, f.sent.size)
    }
  }

  @Test
  fun aRefusedRefreshSignsOutButAnOutageKeepsTheSession() = runTest {
    fixture { f ->
      f.request("pocketcasts/session", "PUT", signIn)
      f.platform.clock.now += 1.hours
      f.refreshStatus = 503U
      assertEquals(502U, f.call("/user/in_progress").code)
      assertTrue(f.request("pocketcasts/session").body.utf8().contains("\"signedIn\":true"))
      f.refreshStatus = 401U
      assertEquals(401U, f.call("/user/in_progress").code)
      assertTrue(f.request("pocketcasts/session").body.utf8().contains("\"signedIn\":false"))
      assertEquals(401U, f.call("/user/in_progress").code)
    }
  }

  @Test
  fun anUpstreamErrorAfterARefreshKeepsTheRefreshedToken() = runTest {
    fixture { f ->
      f.request("pocketcasts/session", "PUT", signIn)
      f.platform.clock.now += 1.hours
      f.callStatus = 500U
      assertEquals(502U, f.call("/user/in_progress").code)
      f.callStatus = 200U
      val before = f.sent.size
      assertEquals(200U, f.call("/user/in_progress").code)
      assertEquals("Bearer private-fixture-access-2", f.bearer(f.sent.last()))
      assertEquals(before + 1, f.sent.size)
    }
  }

  @Test
  fun aFailedSignInStoresNothingAndSignOutForgets() = runTest {
    fixture { f ->
      f.loginStatus = 401U
      assertEquals(401U, f.request("pocketcasts/session", "PUT", signIn).code)
      assertTrue(f.request("pocketcasts/session").body.utf8().contains("\"signedIn\":false"))
      f.loginStatus = 200U
      f.request("pocketcasts/session", "PUT", signIn)
      assertEquals(200U, f.request("pocketcasts/session", "DELETE", "{}").code)
      assertTrue(f.request("pocketcasts/session").body.utf8().contains("\"signedIn\":false"))
      assertEquals(401U, f.call("/user/in_progress").code)
    }
  }

  @Test
  fun removalForgetsTheSignIn() = runTest {
    fixture { f ->
      f.request("pocketcasts/session", "PUT", signIn)
      AppServicesApp.Factory(
          SessionKeys.parse("test:" + Base64.getEncoder().encodeToString(ByteArray(32) { 3 }))
        )
        .create(f.platform)
        .beforeRemove()
      assertTrue(f.request("pocketcasts/session").body.utf8().contains("\"signedIn\":false"))
    }
  }
}
