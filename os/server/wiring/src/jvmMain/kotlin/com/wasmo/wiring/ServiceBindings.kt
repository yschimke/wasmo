package com.wasmo.wiring

import com.wasmo.api.routes.RouteCodec
import com.wasmo.common.routes.RealRouteCodec
import com.wasmo.computers.AppCatalog
import com.wasmo.computers.loadDefaultAppCatalogFromResources
import com.wasmo.framework.ContentTypeDatabase
import com.wasmo.framework.MDN
import com.wasmo.http.OkHttpClientHttpService
import com.wasmo.identifiers.AppSlug
import com.wasmo.identifiers.Deployment
import com.wasmo.identifiers.HostnamePatterns
import com.wasmo.identifiers.OsScope
import com.wasmo.identifiers.hostnamePatterns
import com.wasmo.journal.server.JournalWasmoApp
import com.wasmo.wasm.AppLoader
import com.wasmo.wasm.JvmAppLoader
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import ee.schimke.wasmo.services.AppServicesApp
import ee.schimke.wasmo.services.appProxies
import ee.schimke.wasmo.services.serviceApps
import io.ktor.server.application.Application
import io.ktor.server.engine.EmbeddedServer
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import okhttp3.Call
import okhttp3.Dns
import okhttp3.OkHttpClient
import okio.FileSystem
import wasmo.app.WasmoApp
import wasmo.http.HttpService

@BindingContainer
abstract class ServiceBindings {
  @Binds
  abstract fun bindCallFactory(real: OkHttpClient): Call.Factory

  @Binds
  abstract fun bindHttpClient(real: OkHttpClientHttpService): HttpService

  @Binds
  abstract fun bindRouteCodecFactory(real: RealRouteCodec.Factory): RouteCodec.Factory

  @Binds
  abstract fun bindAppLoader(real: JvmAppLoader): AppLoader

  companion object {

    @Provides
    @SingleIn(OsScope::class)
    fun provideClock(): Clock = Clock.System

    @Provides
    @SingleIn(OsScope::class)
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
      .dns(LocalhostSubdomainsDns(Dns.SYSTEM))
      .build()

    @Provides
    @SingleIn(OsScope::class)
    fun provideApplication(
      server: EmbeddedServer<*, *>,
    ): Application = server.application

    @Provides
    @SingleIn(OsScope::class)
    fun provideCoroutineScope(): CoroutineScope = CoroutineScope(Dispatchers.Default)

    @Provides
    @SingleIn(OsScope::class)
    fun provideAppCatalog(deployment: Deployment): AppCatalog =
      loadDefaultAppCatalogFromResources(deployment.baseUrl)

    @Provides
    @SingleIn(OsScope::class)
    fun provideFileSystem(): FileSystem = FileSystem.SYSTEM

    @Provides
    @SingleIn(OsScope::class)
    fun provideWasmoAppFactories(): Map<AppSlug, WasmoApp.Factory> = buildMap {
      val sessionKeys = ee.schimke.wasmo.services.SessionKeys.parse(System.getenv("WASMO_SESSION_KEYS"))
      val proxies = appProxies(System.getenv("WASMO_APP_PROXIES"))
      put(AppSlug("journal"), JournalWasmoApp.Factory())
      // Temporary JVM simulation until the platform can load third-party app components.
      for (name in serviceApps(System.getenv("WASMO_SERVICE_APPS"))) {
        val slug = AppSlug(name)
        require(slug !in this) { "App services cannot replace an existing built-in app: $slug" }
        put(slug, AppServicesApp.Factory(sessionKeys, proxies[name].orEmpty()))
      }
    }

    @Provides
    @SingleIn(OsScope::class)
    fun provideContentTypeDatabase(): ContentTypeDatabase = ContentTypeDatabase.MDN

    @Provides
    @SingleIn(OsScope::class)
    fun provideHostnamePatterns(deployment: Deployment): HostnamePatterns =
      deployment.hostnamePatterns()
  }
}
