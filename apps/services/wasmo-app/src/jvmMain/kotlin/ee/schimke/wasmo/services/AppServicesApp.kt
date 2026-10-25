package ee.schimke.wasmo.services

import wasmo.app.Platform
import wasmo.app.WasmoApp
import wasmo.http.HttpService
import wasmo.sql.SqlService
import wit.wasmo.http.v0_1_0.HttpRequest
import wit.wasmo.http.v0_1_0.HttpResponse

class AppServicesApp internal constructor(private val sql: SqlService) : WasmoApp() {
  // The current JVM loader creates an app on every call. Own database handles per invocation,
  // rather than retaining a connection pool on an app instance the loader never closes.
  override val httpService =
    object : HttpService {
      override suspend fun execute(request: HttpRequest): HttpResponse =
        sql.getOrCreate().use {
          AppServicesHttpService(AppServicesDb(it)).execute(request)
        }
    }

  override suspend fun afterInstall(oldVersion: Long, newVersion: Long) {
    sql.getOrCreate().use { AppServicesDb(it).ensureSchema() }
  }

  class Factory : WasmoApp.Factory {
    override suspend fun create(platform: Platform): AppServicesApp =
      AppServicesApp(platform.sqlService)
  }
}
