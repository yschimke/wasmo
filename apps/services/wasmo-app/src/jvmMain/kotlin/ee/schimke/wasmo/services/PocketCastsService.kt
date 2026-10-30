package ee.schimke.wasmo.services

import java.io.IOException
import kotlin.time.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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

/**
 * Private per-install Pocket Casts custody for apps whose browser build can't call Pocket Casts:
 * its API answers cross-origin requests only from pocketcasts.com. The server signs in, keeps the
 * refresh token sealed and makes a fixed set of read calls for the owner. It is not a general proxy:
 * one origin, four paths, and the password is never stored. See POCKET-CASTS.md.
 */
internal class PocketCastsService(
  private val db: AppServicesDb,
  private val keys: SessionKeys,
  private val http: HttpService,
  private val clock: Clock,
) {
  /** Removing the app forgets the sign-in. Pocket Casts has no revocation endpoint to call. */
  suspend fun forgetForRemoval(): Unit = db.transaction { connection ->
    lock(connection)
    save(connection, null)
  }

  suspend fun execute(route: String, request: Request): Response = db.transaction { connection ->
    val sealed = lock(connection)
    val current = sealed?.let { open(connection, it) }
    if (route == "pocketcasts/session" && request.method == "GET") {
      return@transaction json(status(current))
    }
    val input =
      try {
        val value = request.body?.utf8() ?: "{}"
        require(value.length <= 24 * 1024)
        Json.parseToJsonElement(value).jsonObject
      } catch (_: Exception) {
        throw Failure(400U, "invalid-pocketcasts-request")
      }
    when {
      route == "pocketcasts/session" && request.method == "PUT" -> {
        val email = input.string("email").trim()
        val password = input.string("password")
        if (email.isEmpty() || email.length > 320 || password.isEmpty() || password.length > 1024)
          throw Failure(400U, "invalid-pocketcasts-request")
        val response =
          send(
            "POST",
            "/user/login_pocket_casts",
            null,
            buildJsonObject {
                put("email", email)
                put("password", password)
                put("scope", SCOPE)
              }
              .toString(),
          )
        if (response.code in 400U..499U) throw Failure(401U, "pocketcasts-sign-in-failed")
        if (response.code != 200U) throw Failure(502U, UNAVAILABLE)
        val session = session(response, email, previousRefresh = null)
        save(connection, session)
        json(status(session))
      }
      route == "pocketcasts/session" && request.method == "DELETE" -> {
        save(connection, null)
        json(status(null))
      }
      route == "pocketcasts/call" && request.method == "POST" -> {
        var session = current ?: throw Failure(401U, SIGN_IN)
        val path = input.string("path")
        val method = CALLS[path] ?: throw Failure(400U, "pocketcasts-path-not-allowed")
        val body =
          if (method == "GET") null
          else {
            val value = input["body"] ?: JsonObject(emptyMap())
            if (value !is JsonObject) throw Failure(400U, "invalid-pocketcasts-request")
            value.toString()
          }
        val expires = session["expires"]?.jsonPrimitive?.longOrNull ?: 0
        if (expires - clock.now().toEpochMilliseconds() < 60_000) {
          session = refresh(connection, session) ?: return@transaction error(401U, SIGN_IN)
        }
        // From here a refreshed token may be saved. Failures answer instead of throwing, so the
        // transaction commits it: Pocket Casts may have rotated the old refresh token.
        val response =
          try {
            var response = send(method, path, session.string("accessToken"), body)
            // The access token lasts an hour and may be revoked early; refresh once and retry.
            if (response.code == 401U || response.code == 403U) {
              session = refresh(connection, session) ?: return@transaction error(401U, SIGN_IN)
              response = send(method, path, session.string("accessToken"), body)
            }
            response
          } catch (_: IOException) {
            return@transaction error(502U, UNAVAILABLE)
          } catch (failure: Failure) {
            return@transaction error(failure.status, failure.code)
          }
        if (response.code == 401U || response.code == 403U) {
          save(connection, null)
          return@transaction error(401U, SIGN_IN)
        }
        if (response.code != 200U) return@transaction error(502U, UNAVAILABLE)
        // Re-encrypt during a staged key rotation, so routine reads stay cheap.
        if (!keys.isActive(sealed!!)) save(connection, session)
        Response(code = 200U, headers = HEADERS, body = response.body)
      }
      else -> throw Failure(404U, "not-found")
    }
  }

  /** The refreshed session, saved; null when Pocket Casts refused the refresh token. */
  private suspend fun refresh(connection: SqlConnection, current: JsonObject): JsonObject? {
    val response =
      send(
        "POST",
        "/user/token",
        null,
        buildJsonObject {
            put("grant_type", "refresh_token")
            put("refresh_token", current.string("refreshToken"))
            put("scope", SCOPE)
          }
          .toString(),
      )
    // Only a refusal ends custody; an outage keeps it for the next attempt.
    if (response.code in 400U..499U) {
      save(connection, null)
      return null
    }
    if (response.code != 200U) throw Failure(502U, UNAVAILABLE)
    return session(response, current.string("email"), current.string("refreshToken")).also {
      save(connection, it)
    }
  }

  private fun session(response: Response, email: String, previousRefresh: String?): JsonObject {
    try {
      val text = response.body.utf8()
      require(boundedJsonDepth(text))
      val body = Json.parseToJsonElement(text).jsonObject
      fun text(name: String) = body[name]?.jsonPrimitive?.contentOrNull.orEmpty()
      val access = text("accessToken").ifBlank { text("token") }
      val refresh = text("refreshToken").ifBlank { previousRefresh.orEmpty() }
      val expiresIn = body["expiresIn"]?.jsonPrimitive?.longOrNull ?: 3600
      require(access.isNotEmpty() && access.length <= 16 * 1024)
      require(refresh.isNotEmpty() && refresh.length <= 16 * 1024 && expiresIn in 1..86400)
      return buildJsonObject {
        put("email", text("email").ifBlank { email })
        put("refreshToken", refresh)
        put("accessToken", access)
        put("expires", clock.now().toEpochMilliseconds() + expiresIn * 1000)
      }
    } catch (_: Exception) {
      throw Failure(502U, "invalid-pocketcasts-response")
    }
  }

  private suspend fun send(method: String, path: String, bearer: String?, body: String?) =
    http.execute(
      HttpRequest(
        method = method,
        url = ORIGIN + path,
        headers =
          buildList {
            add(Header("Content-Type", "application/json"))
            if (bearer != null) add(Header("Authorization", "Bearer $bearer"))
          },
        body = body?.encodeUtf8(),
      )
    )

  private suspend fun lock(connection: SqlConnection): String? {
    connection.execute("SET LOCAL lock_timeout = '3s'")
    return connection
      .executeQuery("SELECT sealed FROM wasmo_services_pocketcasts WHERE singleton = 1 FOR UPDATE")
      .use { it.next()!!.getString(0) }
  }

  private suspend fun open(connection: SqlConnection, sealed: String): JsonObject =
    try {
      Json.parseToJsonElement(keys.open(context(connection), sealed, PURPOSE)).jsonObject
    } catch (_: Exception) {
      throw Failure(503U, "session-key-unavailable")
    }

  private suspend fun save(connection: SqlConnection, session: JsonObject?) {
    val sealed = session?.let { keys.seal(context(connection), it.toString(), PURPOSE) }
    connection.execute("UPDATE wasmo_services_pocketcasts SET sealed = $1 WHERE singleton = 1") {
      bindString(0, sealed)
    }
  }

  // Binds ciphertext to this install's database, like the HA session.
  private suspend fun context(connection: SqlConnection): String =
    connection.executeQuery("SELECT current_database()::TEXT").use { it.next()!!.getString(0)!! }

  private fun status(session: JsonObject?) = buildJsonObject {
    put("signedIn", session != null)
    if (session != null) put("email", session.string("email"))
  }

  private fun error(status: UInt, code: String) =
    Response(code = status, headers = HEADERS, body = """{"error":"$code"}""".encodeUtf8())

  private fun JsonObject.string(key: String): String =
    try {
      this[key]?.jsonPrimitive?.content ?: throw Failure(400U, "invalid-pocketcasts-request")
    } catch (failure: Failure) {
      throw failure
    } catch (_: Exception) {
      throw Failure(400U, "invalid-pocketcasts-request")
    }

  private fun json(body: JsonObject) =
    HttpResponse(code = 200U, headers = HEADERS, body = body.toString().encodeUtf8())

  class Failure(val status: UInt, val code: String) : Exception()

  companion object {
    private const val ORIGIN = "https://api.pocketcasts.com"
    private const val SCOPE = "webplayer"
    private const val PURPOSE = "wasmo-pocketcasts-session-v1"
    private const val SIGN_IN = "sign-in-required"
    private const val UNAVAILABLE = "pocketcasts-unavailable"

    /** The read calls Cadence makes, with their methods. Nothing that writes to the account. */
    private val CALLS =
      mapOf(
        "/up_next/list" to "POST",
        "/user/in_progress" to "POST",
        "/user/playlists" to "GET",
        "/user/episode" to "POST",
      )

    private val HEADERS =
      listOf(
        Header("Content-Type", "application/json"),
        Header("Cache-Control", "no-store"),
        Header("X-Content-Type-Options", "nosniff"),
      )
  }
}
