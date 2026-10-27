package ee.schimke.wasmo.services

import java.net.URI
import java.net.URLEncoder
import java.util.UUID
import kotlin.time.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okio.ByteString.Companion.encodeUtf8
import wasmo.http.HttpRequest
import wasmo.http.HttpResponse
import wasmo.http.HttpService
import wasmo.sql.SqlConnection
import wit.wasmo.http.v0_1_0.Header
import wit.wasmo.http.v0_1_0.HttpRequest as Request
import wit.wasmo.http.v0_1_0.HttpResponse as Response

/** Private per-install HA custody. No object store, public route, arbitrary proxy or HA states. */
internal class HaSessionService(
  private val db: AppServicesDb,
  private val keys: SessionKeys,
  private val http: HttpService,
  private val clock: Clock,
  private val applicationUrl: String,
) {
  /**
   * Called when the owner removes the app: revoke the grant at HA and forget it, keeping the
   * sign-out tombstone so a reinstall can't resume it. HA being unreachable doesn't keep custody.
   */
  suspend fun revokeForRemoval(): Unit = db.transaction { connection ->
    val row = lock(connection)
    val current = row.open()
    if (current != null) {
      try {
        revoke(current)
      } catch (_: Exception) {
      }
    }
    save(connection, UUID.randomUUID().toString(), null)
  }

  suspend fun execute(route: String, request: Request): Response = db.transaction { connection ->
    val row = lock(connection)
    val current = row.open()
    if (route == "ha/session" && request.method == "GET") {
      return@transaction json(
        buildJsonObject {
          put("signedIn", current != null)
          put("sessionId", row.id)
          if (current != null) put("hassUrl", current.string("hassUrl"))
        }
      )
    }
    val input =
      try {
        val value = request.body?.utf8() ?: "{}"
        require(value.length <= 24 * 1024)
        Json.parseToJsonElement(value).jsonObject
      } catch (_: Exception) {
        throw Failure(400U, "invalid-session-request")
      }
    when {
      (route == "ha/session" && request.method == "PUT") ||
        (route == "ha/session/import" && request.method == "POST") -> {
        // A sign-out tombstone prevents a second device's old browser tokens resurrecting it.
        if (route.endsWith("/import") && row.initialized) throw Failure(409U, "already-initialized")
        val origin = httpsOrigin(input.string("hassUrl"))
        val clientId = httpsOrigin(applicationUrl) + "/"
        if (input.string("clientId") != clientId) throw Failure(400U, "invalid-client")
        val refreshToken = input.string("refreshToken")
        if (refreshToken.isEmpty() || refreshToken.length > 16 * 1024)
          throw Failure(400U, "invalid-token")
        // Validate at HA before committing custody; do not trust browser-supplied access tokens.
        val session = refresh(origin, clientId, refreshToken)
        // Switching servers or accounts must not leave the previous grant live at HA.
        if (current != null && current.string("refreshToken") != session.string("refreshToken")) {
          try {
            revoke(current)
          } catch (_: Exception) {
          }
        }
        val id = UUID.randomUUID().toString()
        save(connection, id, keys.seal(row.context, session.toString()))
        json(
          buildJsonObject {
            put("signedIn", true)
            put("sessionId", id)
            put("hassUrl", origin)
          }
        )
      }
      route == "ha/token" && request.method == "POST" -> {
        if (current == null || input.string("sessionId") != row.id)
          throw Failure(401U, "session-changed")
        val expires = current["expires"]?.jsonPrimitive?.longOrNull ?: 0
        val session =
          if (expires - clock.now().toEpochMilliseconds() > 30_000) current
          else {
            try {
              refresh(
                current.string("hassUrl"),
                current.string("clientId"),
                current.string("refreshToken"),
              )
            } catch (failure: Failure) {
              if (failure.status != 401U) throw failure
              // Commit invalid-token removal; throwing from the transaction would roll it back.
              save(connection, UUID.randomUUID().toString(), null)
              return@transaction json(buildJsonObject { put("error", "sign-in-required") }, 401U)
            }
          }
        // Re-encrypt cache hits only during a staged rotation, so routine reads stay cheap.
        if (session !== current || !keys.isActive(row.sealed!!)) {
          save(connection, row.id, keys.seal(row.context, session.toString()))
        }
        json(
          buildJsonObject {
            put("access_token", session.string("accessToken"))
            put("expires", session["expires"]!!)
            put("hassUrl", session.string("hassUrl"))
            put("sessionId", row.id)
          }
        )
      }
      route == "ha/session" && request.method == "DELETE" -> {
        val expected = input.string("sessionId")
        // An acknowledged response may be lost. Retrying a completed removal is safe, while
        // an old device must never remove a different active session.
        if (current == null) {
          if (!row.initialized) save(connection, UUID.randomUUID().toString(), null)
          return@transaction json(buildJsonObject { put("signedIn", false) })
        }
        if (expected != row.id) throw Failure(409U, "session-changed")
        // "forget" lets the owner sign out of a Home Assistant that is down or gone for good; the
        // grant then stays valid at HA until it expires or is removed there.
        val forget = input["forget"]?.jsonPrimitive?.booleanOrNull == true
        val revoked =
          try {
            revoke(current)
            true
          } catch (failure: Exception) {
            if (!forget) throw failure
            false
          }
        save(connection, UUID.randomUUID().toString(), null)
        json(
          buildJsonObject {
            put("signedIn", false)
            put("revoked", revoked)
          }
        )
      }
      else -> throw Failure(404U, "not-found")
    }
  }

  private suspend fun lock(connection: SqlConnection): Stored {
    connection.execute("SET LOCAL lock_timeout = '3s'")
    return connection
      .executeQuery(
        "SELECT initialized, session_id, sealed, current_database()::TEXT FROM wasmo_services_ha WHERE singleton = 1 FOR UPDATE"
      )
      .use {
        val row = it.next()!!
        Stored(row.getBool(0)!!, row.getString(1)!!, row.getString(2), row.getString(3)!!)
      }
  }

  private fun Stored.open(): JsonObject? =
    sealed?.let {
      try {
        Json.parseToJsonElement(keys.open(context, it)).jsonObject
      } catch (_: Exception) {
        throw Failure(503U, "session-key-unavailable")
      }
    }

  /** Revokes [session]'s refresh token at HA, or throws [Failure] if HA doesn't confirm it. */
  private suspend fun revoke(session: JsonObject) {
    var response =
      http.execute(
        HttpRequest(
          method = "POST",
          url = session.string("hassUrl") + "/auth/revoke",
          headers = FORM_HEADERS,
          body = form(mapOf("token" to session.string("refreshToken"))).encodeUtf8(),
        )
      )
    // Older HA releases support revocation only on the token endpoint.
    if (response.code == 404U)
      response =
        http.execute(
          HttpRequest(
            method = "POST",
            url = session.string("hassUrl") + "/auth/token",
            headers = FORM_HEADERS,
            body =
              form(mapOf("token" to session.string("refreshToken"), "action" to "revoke"))
                .encodeUtf8(),
          )
        )
    if (response.code != 200U) throw Failure(502U, "ha-unavailable")
  }

  private suspend fun refresh(origin: String, clientId: String, token: String): JsonObject {
    val response =
      http.execute(
        HttpRequest(
          method = "POST",
          url = origin + "/auth/token",
          headers = FORM_HEADERS,
          body =
            form(
                mapOf(
                  "grant_type" to "refresh_token",
                  "refresh_token" to token,
                  "client_id" to clientId,
                )
              )
              .encodeUtf8(),
        )
      )
    if (response.code == 400U || response.code == 401U || response.code == 403U)
      throw Failure(401U, "sign-in-required")
    if (response.code != 200U) throw Failure(502U, "ha-unavailable")
    try {
      val text = response.body.utf8()
      require(boundedJsonDepth(text))
      val body = Json.parseToJsonElement(text).jsonObject
      val access = body.string("access_token")
      val expiresIn = body["expires_in"]?.jsonPrimitive?.longOrNull ?: 1800
      require(access.isNotEmpty() && access.length <= 16 * 1024 && expiresIn in 1..86400)
      val refreshToken = body["refresh_token"]?.jsonPrimitive?.content ?: token
      require(refreshToken.isNotEmpty() && refreshToken.length <= 16 * 1024)
      return buildJsonObject {
        put("hassUrl", origin)
        put("clientId", clientId)
        put("refreshToken", refreshToken)
        put("accessToken", access)
        put("expires", clock.now().toEpochMilliseconds() + expiresIn * 1000)
      }
    } catch (_: Exception) {
      throw Failure(502U, "invalid-ha-response")
    }
  }

  private suspend fun save(connection: SqlConnection, id: String, sealed: String?) {
    connection.execute(
      "UPDATE wasmo_services_ha SET initialized = TRUE, session_id = $1, sealed = $2 WHERE singleton = 1"
    ) {
      bindString(0, id)
      bindString(1, sealed)
    }
  }

  private fun JsonObject.string(key: String): String =
    try {
      this[key]?.jsonPrimitive?.content ?: throw Failure(400U, "invalid-session-request")
    } catch (failure: Failure) {
      throw failure
    } catch (_: Exception) {
      throw Failure(400U, "invalid-session-request")
    }

  private fun httpsOrigin(value: String): String {
    try {
      val uri = URI(value)
      require(
        uri.scheme == "https" &&
          uri.host != null &&
          uri.rawUserInfo == null &&
          uri.rawFragment == null
      )
      require(uri.rawQuery == null && uri.rawPath in listOf("", "/"))
      require(uri.port == -1 || uri.port in 1..65535)
      return "https://${uri.rawAuthority.lowercase()}".removeSuffix(":443")
    } catch (_: Exception) {
      throw Failure(400U, "invalid-ha-origin")
    }
  }

  private fun form(values: Map<String, String>) =
    values.entries.joinToString("&") {
      URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
    }

  private fun json(body: JsonObject, status: UInt = 200U) =
    HttpResponse(
      code = status,
      headers =
        listOf(
          Header("Content-Type", "application/json"),
          Header("Cache-Control", "no-store"),
          Header("X-Content-Type-Options", "nosniff"),
        ),
      body = body.toString().encodeUtf8(),
    )

  private class Stored(
    val initialized: Boolean,
    val id: String,
    val sealed: String?,
    val context: String,
  )

  class Failure(val status: UInt, val code: String) : Exception()

  companion object {
    private val FORM_HEADERS = listOf(Header("Content-Type", "application/x-www-form-urlencoded"))
  }
}
