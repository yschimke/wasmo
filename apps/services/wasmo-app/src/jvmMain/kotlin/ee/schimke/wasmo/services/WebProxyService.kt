package ee.schimke.wasmo.services

import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import wasmo.app.WebProxy
import wasmo.http.HttpRequest
import wasmo.http.HttpResponse
import wasmo.http.HttpService
import wit.wasmo.http.v0_1_0.Header
import wit.wasmo.http.v0_1_0.HttpRequest as Request
import wit.wasmo.http.v0_1_0.HttpResponse as Response

/**
 * Forwards an app's browser requests to the web origins its manifest declares, for APIs that refuse
 * cross-origin requests. It is not an open proxy: the release names each origin and route, the
 * operator approves each origin for the install, and only declared headers leave the server —
 * never the owner's Wasmo cookies. See WEB-PROXY.md.
 */
internal class WebProxyService(
  private val http: HttpService,
  declared: List<WebProxy>,
  private val approvedOrigins: Set<String>,
) {
  // Re-check what the manifest checker enforces: a filesystem install skips that check.
  private val declared = declared.filter { NAME.matches(it.name) && ORIGIN.matches(it.origin) }

  /** `{"name":approved}` for each declared proxy, so an app can explain a missing approval. */
  fun capabilities(): String =
    declared.joinToString(",", "{", "}") { "\"${it.name}\":${it.origin in approvedOrigins}" }

  suspend fun execute(request: Request): Response {
    val url =
      try {
        URI(request.url.value)
      } catch (_: URISyntaxException) {
        throw Failure(400U, "invalid-path")
      }
    if (!url.rawPath.startsWith(PREFIX)) throw Failure(400U, "invalid-proxy-path")
    val rest = url.rawPath.removePrefix(PREFIX)
    val name = rest.substringBefore('/')
    val path = rest.removePrefix(name).ifEmpty { "/" }
    val proxy = declared.firstOrNull { it.name == name } ?: throw Failure(404U, "proxy-not-declared")
    if (proxy.origin !in approvedOrigins) throw Failure(403U, "proxy-not-approved")
    if (!safe(path)) throw Failure(400U, "invalid-proxy-path")
    if (proxy.routes.none { it.method == request.method && matches(it.path, path) })
      throw Failure(403U, "proxy-route-not-declared")
    if ((request.body?.size ?: 0) > MAX_REQUEST_BYTES) throw Failure(413U, "too-large")

    val forwarded = ALWAYS_FORWARDED + proxy.requestHeaders.map { it.lowercase() }
    val response =
      try {
        http.execute(
          HttpRequest(
            method = request.method,
            url = proxy.origin + path + (url.rawQuery?.let { "?$it" } ?: ""),
            headers =
              request.headers.filter {
                it.name.lowercase().let { header -> header in forwarded && header !in NEVER }
              },
            body = request.body?.takeIf { request.method != "GET" && request.method != "HEAD" },
          )
        )
      } catch (_: IOException) {
        throw Failure(502U, "proxy-unavailable")
      } catch (_: IllegalArgumentException) {
        // The public HTTPS client refused the destination, e.g. a non-public address.
        throw Failure(502U, "proxy-destination-refused")
      }
    return HttpResponse(
      code = response.code,
      headers =
        response.headers.filter { it.name.lowercase() in RETURNED } +
          listOf(
            Header("Cache-Control", "no-store"),
            Header("X-Content-Type-Options", "nosniff"),
            // The body is another site's content served from the app's origin: never run it.
            Header("Content-Security-Policy", "sandbox; default-src 'none'"),
          ),
      body = response.body,
    )
  }

  private fun safe(path: String): Boolean {
    if (!path.startsWith("/") || "//" in path || '\\' in path) return false
    val lower = path.lowercase()
    if (ENCODED_SEPARATORS.any { it in lower }) return false
    return path.split('/').none { it == "." || it == ".." }
  }

  private fun matches(declared: String, path: String): Boolean {
    if (!declared.endsWith("/**")) return declared == path
    val prefix = declared.removeSuffix("/**")
    return path == prefix || path.startsWith("$prefix/")
  }

  class Failure(val status: UInt, val code: String) : Exception(code)

  companion object {
    const val PREFIX = "/api/wasmo/v1/proxy/"
    private const val MAX_REQUEST_BYTES = 64 * 1024
    private val NAME = Regex("[a-z][a-z0-9-]{0,31}")
    private val ORIGIN =
      Regex("https://[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+(:[1-9][0-9]{0,4})?")
    private val ALWAYS_FORWARDED = setOf("accept", "content-type")
    private val NEVER = setOf("cookie", "host", "origin", "referer", "x-wasmo-api", "content-length")
    private val RETURNED = setOf("content-type", "etag", "last-modified")
    // Encoded '/', '\' and '.' would let a path escape a declared prefix once the origin decodes it.
    private val ENCODED_SEPARATORS = listOf("%2f", "%5c", "%2e")
  }
}
