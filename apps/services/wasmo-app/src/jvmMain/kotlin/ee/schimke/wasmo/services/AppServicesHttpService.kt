package ee.schimke.wasmo.services

import java.net.URI
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okio.ByteString.Companion.encodeUtf8
import wasmo.http.HttpResponse
import wasmo.http.HttpService
import wasmo.http.contentType
import wit.wasmo.http.v0_1_0.Header
import wit.wasmo.http.v0_1_0.HttpRequest
import wit.wasmo.http.v0_1_0.HttpResponse as Response

/** Wasmo routes app-code requests only for the computer owner. No public API or CORS headers. */
class AppServicesHttpService internal constructor(private val db: AppServicesDb) : HttpService {
  override suspend fun execute(request: HttpRequest): Response {
    val path =
      try {
        URI(request.url.value).path ?: return error(400U, "invalid-path")
      } catch (_: IllegalArgumentException) {
        return error(400U, "invalid-path")
      } catch (_: java.net.URISyntaxException) {
        return error(400U, "invalid-path")
      }
    if (!path.startsWith(BASE)) return error(404U, "not-found")
    if (
      request.method != "GET" &&
        request.headers.none {
          it.name.equals("X-Wasmo-Api", ignoreCase = true) && it.value == "1"
        }
    )
      return error(403U, "csrf")
    val isImport = path == "${BASE}kv/import" && request.method == "POST"
    val bodyLimit = if (isImport) AppServicesDb.TOTAL_BYTES else AppServicesDb.VALUE_BYTES
    if ((request.body?.size ?: 0) > bodyLimit) return error(413U, "too-large")

    val route = path.removePrefix(BASE)
    val key = route.removePrefix("kv/")
    if (route.startsWith("kv/") && !KEY.matches(key)) return error(400U, "invalid-key")
    try {
      // Re-check on every request. Refuse a newer schema even if this instance ran before it.
      db.ensureSchema()
      return when {
        route == "capabilities" && request.method == "GET" ->
          json(
            """{"api":1,"backend":"wasmo-app-services","kv":true,"kvImport":true,"haSession":false,"publish":false}"""
          )
        route == "kv" && request.method == "GET" -> json(db.read().toString())
        isImport || (route.startsWith("kv/") && request.method == "PUT") -> {
          if (request.contentType?.substringBefore(';')?.trim() != "application/json") {
            return error(415U, "json-required")
          }
          val body = request.body ?: return error(400U, "invalid-json")
          val text = body.utf8()
          if (!boundedJsonDepth(text)) return error(400U, "json-too-deep")
          val value =
            try {
              Json.parseToJsonElement(text)
            } catch (_: SerializationException) {
              return error(400U, "invalid-json")
            } catch (_: IllegalArgumentException) {
              return error(400U, "invalid-json")
            }
          if (isImport) {
            if (value !is JsonObject || value.keys.any { !KEY.matches(it) })
              return error(400U, "invalid-import")
            if (value.values.any { it.toString().encodeUtf8().size > AppServicesDb.VALUE_BYTES })
              return error(413U, "too-large")
            db.importIfEmpty(value)
          } else {
            db.put(key, value)
          }
          json("", 204U)
        }
        route.startsWith("kv/") && request.method == "DELETE" -> {
          db.delete(key)
          json("", 204U)
        }
        else -> error(404U, "not-found")
      }
    } catch (_: AppServicesDb.AlreadyInitialized) {
      return error(409U, "already-initialized")
    } catch (_: AppServicesDb.NewerSchema) {
      return error(503U, "newer-schema")
    } catch (_: AppServicesDb.StorageLimit) {
      return error(413U, "storage-limit")
    }
  }

  private fun boundedJsonDepth(text: String): Boolean {
    var depth = 0
    var quoted = false
    var escaped = false
    for (char in text) {
      if (quoted) {
        if (escaped) escaped = false
        else if (char == '\\') escaped = true else if (char == '"') quoted = false
      } else {
        when (char) {
          '"' -> quoted = true
          '{',
          '[' -> if (++depth > 64) return false
          '}',
          ']' -> depth--
        }
      }
    }
    return true
  }

  private fun error(status: UInt, name: String) = json("""{"error":"$name"}""", status)

  private fun json(body: String, status: UInt = 200U) =
    HttpResponse(
      code = status,
      headers =
        listOf(
          Header("Content-Type", "application/json"),
          Header("Cache-Control", "no-store"),
          Header("X-Content-Type-Options", "nosniff"),
        ),
      body = body.encodeUtf8(),
    )

  companion object {
    private const val BASE = "/api/wasmo/v1/"
    private val KEY = Regex("[a-z0-9][a-z0-9._-]{0,63}")
  }
}
