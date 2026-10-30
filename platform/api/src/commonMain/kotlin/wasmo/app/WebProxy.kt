package wasmo.app

/** A `[[proxy]]` the installed release declares in its manifest; the host reads it, not the app. */
data class WebProxy(
  val name: String,
  val origin: String,
  val requestHeaders: List<String>,
  val routes: List<Route>,
) {
  /** [path] is exact, or a prefix ending in a `**` segment that matches it and everything below. */
  data class Route(val method: String, val path: String)
}
