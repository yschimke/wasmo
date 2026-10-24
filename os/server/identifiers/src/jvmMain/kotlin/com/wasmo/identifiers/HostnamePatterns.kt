package com.wasmo.identifiers

/**
 * Matches hostnames used to serve Wasmo HTTP calls.
 */
data class HostnamePatterns(
  /** A URL like `https://wasmo.com`. */
  val osHostname: Regex,

  /** Computer URLs like `https://jesse99.wasmo.com`. */
  val computerRegex: Regex,

  /** App install URLs like `https://journal-jesse99.wasmo.com`. */
  val appRegex: Regex,
)

fun Deployment.hostnamePatterns(): HostnamePatterns {
  // The OS host is the base URL's full host, not its registered (eTLD+1) domain. On wasmo.com the
  // two are the same, but a deployment on a subdomain (e.g. wasmo.coo.ee) must anchor routing on
  // that full host, or computer/app hosts like jesse99.wasmo.coo.ee never match and every OS RPC
  // 404s. (okhttp's topPrivateDomain() would give coo.ee here, and is null for hosts like
  // wasmo.localhost.)
  val osHostname = baseUrl.host
  val suffixRegex = Regex.escape(".${osHostname}")
  return HostnamePatterns(
    osHostname = Regex(Regex.escape(osHostname)),
    computerRegex = Regex("${ComputerSlugRegex.pattern}$suffixRegex"),
    appRegex = Regex("${AppSlugRegex.pattern}-${ComputerSlugRegex.pattern}$suffixRegex"),
  )
}
