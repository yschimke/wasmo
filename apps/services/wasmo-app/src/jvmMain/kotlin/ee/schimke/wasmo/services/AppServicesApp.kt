package ee.schimke.wasmo.services

import wasmo.app.Platform
import wasmo.app.WasmoApp
import wasmo.http.HttpService
import wasmo.sql.SqlService
import wit.wasmo.http.v0_1_0.HttpRequest
import wit.wasmo.http.v0_1_0.HttpResponse

class AppServicesApp
internal constructor(private val platform: Platform, private val sessionKeys: SessionKeys?) :
  WasmoApp() {
  private val sql: SqlService
    get() = platform.sqlService

  // The current JVM loader creates an app on every call. Own database handles per invocation,
  // rather than retaining a connection pool on an app instance the loader never closes.
  override val httpService =
    object : HttpService {
      override suspend fun execute(request: HttpRequest): HttpResponse =
        sql.getOrCreate().use {
          val db = AppServicesDb(it)
          val sessions = sessionKeys?.let { keys ->
            platform.publicHttpsService?.let { http ->
              platform.applicationUrl
                ?.takeIf { url -> url.startsWith("https://") }
                ?.let { url ->
                  HaSessionService(db, keys, http, platform.clock, url)
                }
            }
          }
          AppServicesHttpService(db, sessions).execute(request)
        }
    }

  override suspend fun afterInstall(oldVersion: Long, newVersion: Long) {
    sql.getOrCreate().use { AppServicesDb(it).ensureSchema() }
  }

  class Factory(private val sessionKeys: SessionKeys? = null) : WasmoApp.Factory {
    override suspend fun create(platform: Platform): AppServicesApp =
      AppServicesApp(platform, sessionKeys)
  }
}
