package com.wasmo.distributions.homelab

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.wasmo.accounts.CookieSecret
import com.wasmo.accounts.SessionCookieSpec
import com.wasmo.common.catalog.DevelopmentCatalog
import com.wasmo.events.LoggingEventListener
import com.wasmo.framework.registerTopPrivateDomain
import com.wasmo.identifiers.AccountPassword
import com.wasmo.identifiers.Deployment
import com.wasmo.identifiers.DistributionShortCode
import com.wasmo.identifiers.HashingPepper
import com.wasmo.identifiers.OsScope
import com.wasmo.identifiers.Secret
import com.wasmo.ktor.KtorLogger
import com.wasmo.ktor.WasmoKtorConfig
import com.wasmo.objectstore.FileSystemObjectStoreAddress
import com.wasmo.objectstore.ObjectStoreAddress
import com.wasmo.postgresqloperator.LocalPostgresql
import com.wasmo.sendemail.postmark.PostmarkCredentials
import com.wasmo.sendemail.postmark.PostmarkProductionBaseUrl
import com.wasmo.sql.AppDatabaseSecret
import com.wasmo.sql.ProvisioningDb
import com.wasmo.sql.WasmoPostgresqlConfig
import com.wasmo.wiring.Distribution
import com.wasmo.wiring.WasmoService
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.createGraphFactory
import io.ktor.server.engine.EmbeddedServer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.debug.DebugProbes
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.encodeUtf8
import okio.Path.Companion.toPath
import wasmo.sql.SqlDatabase

class HomelabCommand : CliktCommand() {
  val container: Boolean by option("--container")
    .flag("--no-container", default = true)
  val devMode: Boolean by option("--dev-mode")
    .flag("--no-dev-mode", default = false)

  @OptIn(ExperimentalCoroutinesApi::class)
  override fun run() = runBlocking {
    // The public base URL (scheme + host) the OS identifies itself by: it drives the per-computer
    // and per-app hostname patterns, cookie domain, and redirect URLs. Default to the local
    // development host; a deployment behind a real domain sets WASMO_BASE_URL (e.g.
    // "https://wasmo.example.com/"). The internal listen port is configured separately below and
    // is unaffected, so this stays correct behind a TLS-terminating reverse proxy on 443.
    var baseUrl =
      (System.getenv("WASMO_BASE_URL")?.takeIf { it.isNotBlank() } ?: "http://wasmo.localhost:8080/")
        .toHttpUrl()

    // Teach URL decoding this deployment's root host, so <computer>.<host> and
    // <app>-<computer>.<host> requests decode their subdomain instead of 500ing on a custom domain.
    registerTopPrivateDomain(baseUrl.host)

    // Optional: set a password on the bootstrapped (passwordless) admin account, so a homelab
    // exposed on a public domain isn't open to anyone signing in as admin.
    val adminPassword = System.getenv("WASMO_ADMIN_PASSWORD")?.takeIf { it.isNotBlank() }

    val passwordPepper = requireNotNull(System.getenv("WASMO_PASSWORD_PEPPER")).decodeHex()

    // Session cookies and email challenge tokens are HMAC-signed with this key, so it must not be a
    // constant from source. Derive it from the pepper (already a required host secret) unless the
    // operator sets WASMO_COOKIE_SECRET; changing either signs every session out.
    val cookieSecret = System.getenv("WASMO_COOKIE_SECRET")?.takeIf { it.isNotBlank() }?.decodeHex()
      ?: "wasmo-homelab-cookie-secret".encodeUtf8().hmacSha256(passwordPepper)

    // Over HTTPS the session cookie is Secure and hidden from page scripts, so an installed app's
    // origin can't read the owner's session. Plain HTTP (wasmo.localhost) can't use either flag.
    val sessionCookieSpec = when {
      baseUrl.isHttps -> SessionCookieSpec.Https
      else -> SessionCookieSpec.Http
    }

    val deployment = Deployment(
      baseUrl = baseUrl,
      sendFromEmailAddress = "noreply@wasmo.dev",
      distributionShortCode = DistributionShortCode("hl"),
    )

    var postgresqlConfig = WasmoPostgresqlConfig(
      hostname = "localhost",
      adminUser = "postgres",
      adminPassword = Secret("password"),
      adminDatabaseName = "postgres",
      osUser = "wasmo_homelab",
      osPassword = Secret("password"),
      osDatabaseName = "wasmo_homelab",
      appPrefix = deployment.distributionShortCode.shortCode,
    )

    var ktorConfig = WasmoKtorConfig()

    val objectStoreAddress: ObjectStoreAddress

    if (container) {
      baseUrl = baseUrl.newBuilder()
        .port(54400)
        .build()
      postgresqlConfig = postgresqlConfig.copy(
        port = 54401,
      )
      ktorConfig = ktorConfig.copy(
        port = 54400
      )
      objectStoreAddress = FileSystemObjectStoreAddress(
        path = "/wasmo/objectstore".toPath(),
      )
    } else {
      objectStoreAddress = FileSystemObjectStoreAddress(
        path = System.getProperty("user.home").toPath() / ".wasmo",
      )
    }

    if (devMode) {
      // Setup coroutines debugging.
      DebugProbes.install()
      DebugProbes.enableCreationStackTraces = true
      DebugProbes.sanitizeStackTraces = true
      System.setProperty(
        kotlinx.coroutines.DEBUG_PROPERTY_NAME,
        kotlinx.coroutines.DEBUG_PROPERTY_VALUE_ON
      )
    }
    println("devMode: $devMode")
    println("DebugProbes.isInstalled: ${DebugProbes.isInstalled}")
    println("DebugProbes.enableCreationStackTraces: ${DebugProbes.enableCreationStackTraces}")
    println("${kotlinx.coroutines.DEBUG_PROPERTY_NAME}: ${System.getProperty(kotlinx.coroutines.DEBUG_PROPERTY_NAME)}")

    // Captured from createService so the admin-password step below can run after startup (and so
    // after migrations, which create the admin account).
    var homelabGraph: HomelabGraph? = null

    val distribution = object : Distribution() {
      override val postgresqlConfig = postgresqlConfig
      override val ktorConfig = ktorConfig

      override fun createService(
        server: EmbeddedServer<*, *>,
        provisioningDb: ProvisioningDb,
        wasmoDb: SqlDatabase,
      ): WasmoService {
        val logger = KtorLogger(server)
        val eventListener = LoggingEventListener(logger)
        val localPostgresql = when (container) {
          true -> LocalPostgresql.Exec
          else -> LocalPostgresql.None
        }

        val homelabGraphFactory = createGraphFactory<HomelabGraph.Factory>()
        val serviceGraph = homelabGraphFactory.create(
          server = server,
          cookieSecret = CookieSecret(cookieSecret),
          appDatabaseSecret = AppDatabaseSecret("rhubarb".encodeUtf8()),
          postmarkCredentials = PostmarkCredentials(
            baseUrl = PostmarkProductionBaseUrl,
            serverToken = System.getenv("POSTMARK_SERVER_TOKEN") ?: "?",
          ),
          catalog = DevelopmentCatalog,
          wasmoDb = wasmoDb,
          provisioningDb = provisioningDb,
          postgresqlConfig = postgresqlConfig,
          deployment = deployment,
          objectStoreAddress = objectStoreAddress,
          sessionCookieSpec = sessionCookieSpec,
          localPostgresql = localPostgresql,
          logger = logger,
          eventListener = eventListener,
          hashingPepper = HashingPepper(passwordPepper)
        )
        homelabGraph = serviceGraph
        return serviceGraph.wasmoService
      }
    }

    coroutineScope {
      distribution.start()

      // start() has returned, so migrations have run and the admin account exists.
      if (adminPassword != null) {
        homelabGraph?.adminPasswordInitializer?.ensureAdminPassword(AccountPassword(adminPassword))
      }
    }
  }
}
