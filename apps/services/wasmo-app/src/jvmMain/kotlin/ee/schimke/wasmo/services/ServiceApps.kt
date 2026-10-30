package ee.schimke.wasmo.services

/** Explicit operator allowlist of install names; never inferred from an archive or request. */
fun serviceApps(configured: String?): Set<String> =
  configured
    .orEmpty()
    .split(',')
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .onEach {
      require(it.matches(Regex("[a-z][a-z0-9]{0,14}"))) { "Invalid app services install name: $it" }
    }
    .toSet()

/**
 * The operator's proxy approvals, `install=https://origin` pairs separated by commas. An app's
 * manifest declares the routes; an origin it declares stays unusable until it is listed here.
 */
fun appProxies(configured: String?): Map<String, Set<String>> =
  configured
    .orEmpty()
    .split(',')
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .map { entry ->
      val name = entry.substringBefore('=').trim()
      val origin = entry.substringAfter('=', "").trim()
      require(name.matches(Regex("[a-z][a-z0-9]{0,14}"))) { "Invalid app proxy install name: $entry" }
      require(origin.matches(Regex("https://[a-z0-9.-]+(:[0-9]{1,5})?"))) {
        "Invalid app proxy origin: $entry"
      }
      name to origin
    }
    .groupBy({ it.first }, { it.second })
    .mapValues { it.value.toSet() }
