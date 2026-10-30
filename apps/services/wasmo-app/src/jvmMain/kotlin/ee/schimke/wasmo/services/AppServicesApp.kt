package ee.schimke.wasmo.services

import wasmo.app.Platform
import wasmo.app.WasmoApp
import wasmo.http.HttpService
import wasmo.sql.SqlService
import wit.wasmo.http.v0_1_0.HttpRequest
import wit.wasmo.http.v0_1_0.HttpResponse

class AppServicesApp
internal constructor(
  private val platform: Platform,
  private val sessionKeys: SessionKeys?,
  private val proxyOrigins: Set<String>,
) : WasmoApp() {
  private val sql: SqlService
    get() = platform.sqlService

  // The current JVM loader creates an app on every call. Own database handles per invocation,
  // rather than retaining a connection pool on an app instance the loader never closes.
  override val httpService =
    object : HttpService {
      override suspend fun execute(request: HttpRequest): HttpResponse =
        sql.getOrCreate().use {
          val db = AppServicesDb(it)
          AppServicesHttpService(db, sessions(db), pocketCasts(db), webProxy()).execute(request)
        }
    }

  override suspend fun afterInstall(oldVersion: Long, newVersion: Long) {
    sql.getOrCreate().use { AppServicesDb(it).ensureSchema() }
  }

  // Removal keeps settings for a reinstall but must not leave the HA grant or Pocket Casts
  // sign-in behind.
  override suspend fun beforeRemove() {
    sql.getOrCreate().use {
      val db = AppServicesDb(it)
      db.ensureSchema()
      sessions(db)?.revokeForRemoval()
      pocketCasts(db)?.forgetForRemoval()
    }
  }

  private fun sessions(db: AppServicesDb): HaSessionService? {
    val keys = sessionKeys ?: return null
    val http = platform.publicHttpsService ?: return null
    val url = platform.applicationUrl?.takeIf { it.startsWith("https://") } ?: return null
    return HaSessionService(db, keys, http, platform.clock, url)
  }

  private fun pocketCasts(db: AppServicesDb): PocketCastsService? {
    val keys = sessionKeys ?: return null
    val http = platform.publicHttpsService ?: return null
    return PocketCastsService(db, keys, http, platform.clock)
  }

  private suspend fun webProxy(): WebProxyService? {
    val http = platform.publicHttpsService ?: return null
    return WebProxyService(http, platform.webProxies(), proxyOrigins)
  }

  /** [proxyOrigins] are the operator's approvals for this install; see [appProxies]. */
  class Factory(
    private val sessionKeys: SessionKeys? = null,
    private val proxyOrigins: Set<String> = setOf(),
  ) : WasmoApp.Factory {
    override suspend fun create(platform: Platform): AppServicesApp =
      AppServicesApp(platform, sessionKeys, proxyOrigins)
  }
}
