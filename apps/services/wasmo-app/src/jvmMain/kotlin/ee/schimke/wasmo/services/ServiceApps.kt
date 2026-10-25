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
