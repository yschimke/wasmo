package ee.schimke.wasmo.services

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

class HaSessionServiceTest {
  private val keys =
    SessionKeys.parse("test:" + Base64.getEncoder().encodeToString(ByteArray(32) { 3 }))!!
  private val input =
    """{"hassUrl":"https://ha.example.test","clientId":"https://terrazzo-owner.example/","refreshToken":"private-fixture-refresh"}"""

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
    var refreshes = 0
    var revokes = 0
    var status = 200U

    init {
      platform.applicationUrl = "https://terrazzo-owner.example/"
      platform.publicHttpsService =
        FakeHttpService().apply {
          this += FakeHttpService.Handler { request ->
            if (request.url.value.endsWith("/auth/revoke")) {
              revokes++
              HttpResponse(code = status)
            } else {
              refreshes++
              HttpResponse(
                code = status,
                body =
                  """{"access_token":"private-fixture-access","expires_in":1800}""".encodeUtf8(),
              )
            }
          }
        }
    }

    suspend fun request(
      path: String,
      method: String = "GET",
      body: String? = null,
      csrf: Boolean = true,
    ) =
      AppServicesApp.Factory(
          SessionKeys.parse("test:" + Base64.getEncoder().encodeToString(ByteArray(32) { 3 }))
        )
        .create(platform)
        .httpService
        .execute(
          HttpRequest(
            method = method,
            // The trusted deployment URL, rather than this request host/scheme, binds client_id.
            url = "http://internal-untrusted-host/api/wasmo/v1/$path",
            headers =
              buildList {
                add(Header("Content-Type", "application/json"))
                if (csrf) add(Header("X-Wasmo-Api", "1"))
              },
            body = body?.encodeUtf8(),
          )
        )

    suspend fun id() =
      Json.parseToJsonElement(request("ha/session").body.utf8())
        .jsonObject["sessionId"]!!
        .jsonPrimitive
        .content
  }

  private suspend fun fixture(block: suspend (Fixture) -> Unit) {
    FakeSqlService("wasmo_ha_sessions_test").use { block(Fixture(it)) }
  }

  @Test
  fun encryptedSessionSurvivesAppRecreationWithoutLeakingIntoKvOrMetadata() = runTest {
    fixture { f ->
      assertEquals(200U, f.request("ha/session", "PUT", input).code)
      assertEquals(1, f.refreshes)
      val status = f.request("ha/session").body.utf8()
      assertTrue(status.contains("https://ha.example.test"))
      assertFalse(status.contains("private-fixture"))
      assertEquals("{}", f.request("kv").body.utf8())
      val id = f.id()
      val token = f.request("ha/token", "POST", """{"sessionId":"$id"}""")
      assertTrue(token.body.utf8().contains("private-fixture-access"))
      assertFalse(token.body.utf8().contains("private-fixture-refresh"))
      assertEquals(1, f.refreshes)
      f.platform.sqlService.getOrCreate().newConnection().use { connection ->
        val sealed =
          connection.executeQuery("SELECT sealed FROM wasmo_services_ha").use {
            it.next()!!.getString(0)!!
          }
        assertFalse(sealed.contains("private-fixture"))
        assertFalse(sealed.contains("ha.example"))
      }
      f.platform.clock.now += 1.hours
      assertEquals(200U, f.request("ha/token", "POST", """{"sessionId":"$id"}""").code)
      assertEquals(2, f.refreshes)
      assertTrue(f.request("capabilities").body.utf8().contains("\"publish\":false"))
    }
  }

  @Test
  fun signoutRevokesAndTombstonePreventsLegacyResurrectionAndLateRefresh() = runTest {
    fixture { f ->
      assertEquals(200U, f.request("ha/session/import", "POST", input).code)
      val id = f.id()
      assertEquals(200U, f.request("ha/session", "DELETE", """{"sessionId":"$id"}""").code)
      assertEquals(1, f.revokes)
      assertEquals(200U, f.request("ha/session", "DELETE", """{"sessionId":"$id"}""").code)
      assertEquals(1, f.revokes)
      assertNotEquals(id, f.id())
      assertEquals(409U, f.request("ha/session/import", "POST", input).code)
      assertEquals(401U, f.request("ha/token", "POST", """{"sessionId":"$id"}""").code)
      assertEquals(1, f.refreshes)
      assertFalse(f.request("ha/session").body.utf8().contains("https://ha.example"))
      // A new explicit sign-in is permitted after sign-out; a stale sign-out cannot remove it.
      assertEquals(200U, f.request("ha/session", "PUT", input).code)
      assertEquals(409U, f.request("ha/session", "DELETE", """{"sessionId":"$id"}""").code)
    }
  }

  @Test
  fun unavailableHaDoesNotClaimSignoutAndInvalidRefreshIsForgotten() = runTest {
    fixture { f ->
      f.request("ha/session", "PUT", input)
      val id = f.id()
      f.status = 503U
      assertEquals(502U, f.request("ha/session", "DELETE", """{"sessionId":"$id"}""").code)
      assertEquals(id, f.id())
      f.status = 400U
      f.platform.clock.now += 1.hours
      assertEquals(401U, f.request("ha/token", "POST", """{"sessionId":"$id"}""").code)
      assertNotEquals(id, f.id())
      assertTrue(f.request("ha/session").body.utf8().contains("\"signedIn\":false"))
    }
  }

  @Test
  fun forgetSignsOutWhenHaCannotConfirmRevocation() = runTest {
    fixture { f ->
      f.request("ha/session", "PUT", input)
      val id = f.id()
      f.status = 503U
      val response =
        f.request("ha/session", "DELETE", """{"sessionId":"$id","forget":true}""").body.utf8()
      assertTrue(response.contains("\"revoked\":false"))
      assertTrue(f.request("ha/session").body.utf8().contains("\"signedIn\":false"))
      assertEquals(409U, f.request("ha/session/import", "POST", input).code)
    }
  }

  @Test
  fun replacingASessionRevokesThePreviousGrant() = runTest {
    fixture { f ->
      f.request("ha/session", "PUT", input)
      assertEquals(0, f.revokes)
      val other = input.replace("private-fixture-refresh", "private-fixture-other")
      assertEquals(200U, f.request("ha/session", "PUT", other).code)
      assertEquals(1, f.revokes)
      // The same grant signing in again is not revoked.
      assertEquals(200U, f.request("ha/session", "PUT", other).code)
      assertEquals(1, f.revokes)
    }
  }

  @Test
  fun removalRevokesAndForgetsEvenWhenHaIsDown() = runTest {
    fixture { f ->
      f.request("ha/session", "PUT", input)
      AppServicesApp.Factory(keys).create(f.platform).beforeRemove()
      assertEquals(1, f.revokes)
      assertTrue(f.request("ha/session").body.utf8().contains("\"signedIn\":false"))
      // A reinstall can't resume the grant from old browser tokens either.
      assertEquals(409U, f.request("ha/session/import", "POST", input).code)

      f.request("ha/session", "PUT", input)
      f.status = 503U
      AppServicesApp.Factory(keys).create(f.platform).beforeRemove()
      assertTrue(f.request("ha/session").body.utf8().contains("\"signedIn\":false"))
    }
  }

  @Test
  fun validatesOriginClientAndCsrfBeforeCallingHa() = runTest {
    fixture { f ->
      assertEquals(403U, f.request("ha/session", "PUT", input, csrf = false).code)
      for (origin in
        listOf(
          "http://ha.example.test",
          "https://user@ha.example.test",
          "https://ha.example.test/api",
          "https://ha.example.test/?query",
        )) {
        assertEquals(
          400U,
          f.request("ha/session", "PUT", input.replace("https://ha.example.test", origin)).code,
        )
      }
      assertEquals(
        400U,
        f.request(
            "ha/session",
            "PUT",
            input.replace("https://terrazzo-owner.example/", "https://another-app.example/"),
          )
          .code,
      )
      assertEquals(0, f.refreshes)
      assertEquals(400U, f.request("ha/session", "PUT", "[".repeat(65)).code)
      assertFalse(f.request("ha/session").body.utf8().contains("private-fixture"))
    }
  }

  @Test
  fun concurrentRefreshesAreSerializedAcrossAppInstances() = runTest {
    fixture { f ->
      f.request("ha/session", "PUT", input)
      val id = f.id()
      f.platform.clock.now += 1.hours
      val responses =
        (1..4).map { async { f.request("ha/token", "POST", """{"sessionId":"$id"}""") } }.awaitAll()
      assertTrue(responses.all { it.code == 200U })
      assertEquals(2, f.refreshes)
    }
  }

  @Test
  fun rejectsExcessivelyNestedHaResponseWithoutSavingIt() = runTest {
    fixture { f ->
      f.platform.publicHttpsService =
        FakeHttpService().apply {
          this += FakeHttpService.Handler {
            HttpResponse(body = ("[".repeat(65) + "0" + "]".repeat(65)).encodeUtf8())
          }
        }
      assertEquals(502U, f.request("ha/session", "PUT", input).code)
      assertTrue(f.request("ha/session").body.utf8().contains("\"signedIn\":false"))
    }
  }

  @Test
  fun supportsOlderHaRevocationOnlyAfterNewEndpointIsMissing() = runTest {
    fixture { f ->
      f.request("ha/session", "PUT", input)
      val id = f.id()
      var fallbackCalled = false
      f.platform.publicHttpsService =
        FakeHttpService().apply {
          this += FakeHttpService.Handler { request ->
            if (request.url.value.endsWith("/auth/revoke")) HttpResponse(code = 404U)
            else {
              assertTrue(request.url.value.endsWith("/auth/token"))
              assertTrue(request.body!!.utf8().contains("action=revoke"))
              fallbackCalled = true
              HttpResponse()
            }
          }
        }
      assertEquals(200U, f.request("ha/session", "DELETE", """{"sessionId":"$id"}""").code)
      assertTrue(fallbackCalled)
      assertTrue(f.request("ha/session").body.utf8().contains("\"signedIn\":false"))
    }
  }

  @Test
  fun explicitSignoutBeforeFirstImportAlsoCreatesATombstone() = runTest {
    fixture { f ->
      assertEquals(200U, f.request("ha/session", "DELETE", """{"sessionId":""}""").code)
      assertEquals(409U, f.request("ha/session/import", "POST", input).code)
      assertEquals(0, f.refreshes)
      assertEquals(200U, f.request("ha/session", "PUT", input).code)
    }
  }
}
