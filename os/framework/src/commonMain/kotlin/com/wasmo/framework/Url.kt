package com.wasmo.framework

data class Url(
  val scheme: String,
  val topPrivateDomain: String,
  val port: Int = defaultPort(scheme),
  val subdomain: String? = null,
  val path: List<String> = listOf(""),
  val query: List<QueryParameter>? = listOf(),
) {
  init {
    require(scheme == "http" || scheme == "https")
    require(path.isNotEmpty())
  }
}

fun defaultPort(scheme: String): Int = when (scheme) {
  "http" -> 80
  "https" -> 443
  else -> error("unexpected scheme: $scheme")
}

data class QueryParameter(
  val name: String,
  val value: String?,
)

expect fun String.decodeUrl(): Url

expect fun Url.encode(): String

/** Return the parts of this URL that can be navigated to without a hard navigation. */
expect fun Url.encodePathAndQuery(): String

// The built-in root hosts that upstream environments are served on.
private val defaultTopPrivateDomains = listOf(
  "wasmo.com",
  "wasmo.dev",
  "wasmo.localhost",
)

private var registeredTopPrivateDomains: List<String> = defaultTopPrivateDomains

/**
 * The root hosts [decodeDomain] recognises, so a host like `kodu.<root>` splits into
 * subdomain `kodu` + root `<root>` instead of being mistaken for a bare root.
 */
val TopPrivateDomains: List<String>
  get() = registeredTopPrivateDomains

/**
 * Register a deployment's own root host (e.g. `wasmo.coo.ee`) so URLs under it decode their
 * subdomain. Without this, a self-hosted deployment on any domain but the built-in three has every
 * `<computer>.<root>` and `<app>-<computer>.<root>` host decode to subdomain `null`, so the client
 * routes it to the OS home (blank page, "snapshot absent" crash) and the server mis-routes app
 * requests to the home route (ClassCastException → 500).
 *
 * Call once at startup before any URL is decoded — on the server from `WASMO_BASE_URL`, in the
 * browser from `routing_context.rootUrl`. Idempotent, and a no-op for the built-in hosts. The
 * registered host is matched ahead of the built-ins so a custom root always wins.
 */
fun registerTopPrivateDomain(host: String) {
  if (host.isBlank() || host in registeredTopPrivateDomains) return
  registeredTopPrivateDomains = listOf(host) + registeredTopPrivateDomains
}

internal fun decodeDomain(hostname: String): Pair<String, String?> {
  for (candidate in TopPrivateDomains) {
    if (!hostname.endsWith(candidate)) continue

    val candidateStart = hostname.length - candidate.length
    if (candidateStart == 0) {
      return candidate to null
    }

    if (candidateStart > 1 || hostname[candidateStart - 1] == '.') {
      return candidate to hostname.take(candidateStart - 1)
    }
  }

  return hostname to null
}
