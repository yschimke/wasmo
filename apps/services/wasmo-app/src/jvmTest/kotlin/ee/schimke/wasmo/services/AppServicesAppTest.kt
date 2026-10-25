package ee.schimke.wasmo.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.ByteString.Companion.encodeUtf8
import wasmo.app.FakePlatform
import wasmo.http.HttpRequest
import wasmo.sql.FakeSqlService
import wasmo.sql.SqlDatabase
import wasmo.sql.SqlService
import wit.wasmo.http.v0_1_0.Header

class AppServicesAppTest {
  // FakeSqlService intentionally caches one handle. Keep it owned by the test fixture while the
  // production app exercises its per-request open/close lifecycle on borrowed handles.
  private class BorrowedSql(private val sql: SqlService) : SqlService {
    var opened = 0
    var closed = 0

    override suspend fun getOrCreate(name: String): SqlDatabase {
      val shared = sql.getOrCreate(name)
      opened++
      return object : SqlDatabase by shared {
        override fun close() {
          closed++
        }
      }
    }

    override fun close() {}
  }

  private suspend fun withApp(block: suspend (AppServicesApp, FakePlatform) -> Unit) {
    FakeSqlService("wasmo_services_test").use { sql ->
      val platform = FakePlatform(BorrowedSql(sql))
      val app = AppServicesApp.Factory().create(platform)
      // Deliberately don't call afterInstall: URL reinstalls don't reliably invoke an update hook.
      block(app, platform)
    }
  }

  private suspend fun AppServicesApp.request(
    path: String,
    method: String = "GET",
    body: String? = null,
    csrf: Boolean = true,
    contentType: String = "application/json",
  ) =
    httpService.execute(
      HttpRequest(
        method = method,
        url = "https://terrazzo-owner.example/api/wasmo/v1/$path",
        headers =
          buildList {
            add(Header("Content-Type", contentType))
            if (csrf) add(Header("X-Wasmo-Api", "1"))
          },
        body = body?.encodeUtf8(),
      )
    )

  @Test
  fun settingsSurviveAppRecreationAndSchemaCheckIsIdempotent() = runTest {
    withApp { app, platform ->
      assertEquals(200U, app.request("capabilities").code)
      assertEquals("{}", app.request("kv").body.utf8())
      assertEquals(204U, app.request("kv/terrazzo.usage.sharing", "PUT", "true").code)
      assertEquals(204U, app.request("kv/terrazzo.usage.ledger", "PUT", "{\"uses\":1}").code)
      app.afterInstall(0, 1)
      app.afterInstall(1, 2)
      val reopened = AppServicesApp.Factory().create(platform)
      val values = Json.parseToJsonElement(reopened.request("kv").body.utf8()).jsonObject
      assertEquals("true", values["terrazzo.usage.sharing"].toString())
      assertEquals("{\"uses\":1}", values["terrazzo.usage.ledger"].toString())
      assertEquals(204U, reopened.request("kv/terrazzo.usage.ledger", "DELETE").code)
      assertEquals(204U, reopened.request("kv/terrazzo.usage.ledger", "DELETE").code)
      assertFalse(reopened.request("kv").body.utf8().contains("ledger"))
    }
  }

  @Test
  fun closesEachInvocationDatabaseHandleIncludingRejectedRequests() = runTest {
    withApp { app, platform ->
      val sql = platform.sqlService as BorrowedSql
      assertEquals(0, sql.opened)
      app.afterInstall(0, 1)
      assertEquals(1, sql.opened)
      assertEquals(1, sql.closed)
      assertEquals(200U, app.request("kv").code)
      assertEquals(403U, app.request("kv/key", "PUT", "true", csrf = false).code)
      assertEquals(3, sql.opened)
      assertEquals(sql.opened, sql.closed)
    }
  }

  @Test
  fun differentAppInstallsKeepSettingsIsolated() = runTest {
    withApp { first, _ ->
      FakeSqlService("wasmo_services_second_app_test").use { sql ->
        val second = AppServicesApp.Factory().create(FakePlatform(BorrowedSql(sql)))
        assertEquals(204U, first.request("kv/setting", "PUT", "\"first-app\"").code)
        assertEquals("{}", second.request("kv").body.utf8())
        assertEquals(204U, second.request("kv/setting", "PUT", "\"second-app\"").code)
        assertEquals("{\"setting\":\"first-app\"}", first.request("kv").body.utf8())
        assertEquals("{\"setting\":\"second-app\"}", second.request("kv").body.utf8())
      }
    }
  }

  @Test
  fun rejectsCsrfInvalidKeysAndMalformedJsonWithoutChangingData() = runTest {
    withApp { app, _ ->
      for (method in listOf("PUT", "DELETE", "POST", "OPTIONS")) {
        assertEquals(403U, app.request("kv/key", method, "true", csrf = false).code)
      }
      for (key in listOf("UPPER", "bad/key", "a".repeat(65), "%2f", "")) {
        assertEquals(400U, app.request("kv/$key", "PUT", "true").code)
      }
      assertEquals(400U, app.request("kv/key", "PUT", "{").code)
      assertEquals(400U, app.request("kv/key", "PUT", "[".repeat(65) + "0" + "]".repeat(65)).code)
      // Brackets inside strings don't consume the structural depth budget.
      assertEquals(204U, app.request("kv/brackets", "PUT", "\"" + "[".repeat(100) + "\"").code)
      assertEquals(204U, app.request("kv/brackets", "DELETE").code)
      assertEquals(415U, app.request("kv/key", "PUT", "true", contentType = "text/plain").code)
      assertEquals("{}", app.request("kv").body.utf8())
    }
  }

  @Test
  fun sizeLimitsAreInBytesAndReplacingOrDeletingReleasesQuota() = runTest {
    withApp { app, _ ->
      assertEquals(413U, app.request("kv/huge", "PUT", "\"" + "é".repeat(131072) + "\"").code)
      val value = "\"" + "x".repeat(250000) + "\""
      repeat(4) { assertEquals(204U, app.request("kv/key$it", "PUT", value).code) }
      assertEquals(413U, app.request("kv/key4", "PUT", value).code)
      assertEquals(204U, app.request("kv/key0", "PUT", "null").code)
      assertEquals(204U, app.request("kv/key4", "PUT", value).code)
      assertEquals(204U, app.request("kv/key4", "DELETE").code)
      assertEquals(204U, app.request("kv/key5", "PUT", value).code)
    }
  }

  @Test
  fun initialImportIsAtomicAndCannotOverwriteAnExistingInstall() = runTest {
    withApp { app, _ ->
      assertEquals(400U, app.request("kv/import", "POST", "{\"valid\":true,\"INVALID\":true}").code)
      assertEquals("{}", app.request("kv").body.utf8())
      assertEquals(
        204U,
        app.request("kv/import", "POST", "{\"sharing\":true,\"ledger\":{\"uses\":1}}").code,
      )
      assertEquals(409U, app.request("kv/import", "POST", "{\"sharing\":false}").code)
      val values = Json.parseToJsonElement(app.request("kv").body.utf8()).jsonObject
      assertEquals("true", values["sharing"].toString())
      assertEquals("{\"uses\":1}", values["ledger"].toString())
    }
  }

  @Test
  fun rejectsNewerSchemaEvenOnAnExistingInstance() = runTest {
    withApp { app, platform ->
      assertEquals(200U, app.request("kv").code)
      platform.sqlService.getOrCreate().newConnection().use {
        it.execute("UPDATE wasmo_services_schema SET version = 3")
      }
      assertEquals(503U, app.request("kv").code)
      assertEquals(503U, app.request("kv/key", "PUT", "true").code)
    }
  }

  @Test
  fun noSecretsOrPublishingAreAdvertisedAndApiResponsesAreNotCacheable() = runTest {
    withApp { app, _ ->
      val response = app.request("capabilities")
      val caps = Json.parseToJsonElement(response.body.utf8()).jsonObject
      assertEquals("false", caps["haSession"]!!.jsonPrimitive.content)
      assertEquals("false", caps["publish"]!!.jsonPrimitive.content)
      assertTrue(response.headers.any { it.name == "Cache-Control" && it.value == "no-store" })
      assertFalse(response.headers.any { it.name.startsWith("Access-Control-") })
      assertEquals(404U, app.request("ha/session").code)
      assertEquals(404U, app.request("publish").code)
    }
  }
}
