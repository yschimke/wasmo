package com.wasmo.framework

/** The `scheme://host[:port]` origin a browser would send in an `Origin` header for [this]. */
fun Url.origin(): String = copy(path = listOf(""), query = null).encode().removeSuffix("/")

/**
 * Rejects a state-changing call unless the browser says it came from [url]'s own origin.
 *
 * Every computer and app host shares one registrable domain, so `SameSite` cookies reach owner
 * actions from any installed app's origin too. Browsers send `Origin` on every POST, so requiring
 * it to match keeps another app (or a link or form elsewhere) from acting as the owner.
 */
fun requireSameOrigin(url: Url, origin: String?) {
  if (origin != url.origin()) {
    throw ArgumentUserException("expected a same-origin request")
  }
}
