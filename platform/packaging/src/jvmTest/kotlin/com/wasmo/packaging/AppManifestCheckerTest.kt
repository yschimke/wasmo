package com.wasmo.packaging

import assertk.Assert
import assertk.assertThat
import assertk.assertions.containsExactly
import com.wasmo.support.issues.Issue
import com.wasmo.support.issues.IssueCollector
import kotlin.test.Test

class AppManifestCheckerTest {
  private val manifest = AppManifest(
    target = "https://wasmo.com/sdk/1",
    version = 35,
  )

  @Test
  fun checkManifest() {
    assertThat(
      manifest.copy(target = "https://wasmo.com/sdk/0"),
    ).failsValidation(
      message =
        """
        |unsupported target 'https://wasmo.com/sdk/0'
        |expected one of $SupportedTargets
        """.trimMargin(),
      href = "target",
    )

    assertThat(
      manifest.copy(version = 0),
    ).failsValidation(
      message =
        """
        |unexpected version 0
        |expected a positive integer
        """.trimMargin(),
      href = "version",
    )
  }

  @Test
  fun checkExternalResourceNotPermittedByDefault() {
    assertThat(
      manifest.copy(
        external_resource = listOf(
          ExternalResource(
            from = "../client/build/dist/js/productionExecutable",
            to = "/assets",
            include = listOf("**/*.js", "**/*.js.map"),
          ),
        ),
      ),
    ).failsValidation(
      message = "external resources are not permitted for this manifest",
      href = "external_resource",
    )
  }

  @Test
  fun checkExternalResource() {
    val appManifestChecker = AppManifestChecker(
      allowExternalResources = true,
    )
    assertThat(
      manifest.copy(
        external_resource = listOf(
          ExternalResource(
            from = "../client/build/dist/js/productionExecutable",
            to = "/assets/../..",
            include = listOf("**/*.js", "**/*.js.map"),
          ),
        ),
      ),
    ).failsValidation(
      message = "target directory must not contain '..' path traversal operators",
      href = "external_resource[0].to",
      appManifestChecker = appManifestChecker,
    )

    assertThat(
      manifest.copy(
        external_resource = listOf(
          ExternalResource(
            from = "../client/build/dist/js/productionExecutable",
            to = "assets",
          ),
        ),
      ),
    ).failsValidation(
      message = "target directory must start with '/'",
      href = "external_resource[0].to",
      appManifestChecker = appManifestChecker,
    )

    assertThat(
      manifest.copy(
        external_resource = listOf(
          ExternalResource(
            from = "/Volumes/media",
            to = "/assets",
            include = listOf("/**/*.mp3"),
          ),
        ),
      ),
    ).failsValidation(
      message = "include must not start with '/'",
      href = "external_resource[0].include[0]",
      appManifestChecker = appManifestChecker,
    )
  }

  @Test
  fun checkLauncher() {
    assertThat(
      manifest.copy(
        launcher = Launcher(
          label = "Recipes",
          maskable_icon_path = "recipes.svg",
        ),
      ),
    ).failsValidation(
      message = "string must start with /",
      href = "launcher.maskable_icon_path",
    )

    assertThat(
      manifest.copy(
        launcher = Launcher(
          label = "Recipes",
          home_path = "home",
        ),
      ),
    ).failsValidation(
      message = "string must start with /",
      href = "launcher.home_path",
    )

    assertThat(
      manifest.copy(
        launcher = Launcher(
          label = "Recipes",
          home_path = "//home",
        ),
      ),
    ).failsValidation(
      message = "string must not start with //",
      href = "launcher.home_path",
    )
  }

  @Test
  fun checkProxy() {
    val proxy = Proxy(
      name = "podcasts",
      origin = "https://api.example.com",
      request_headers = listOf("Authorization"),
      route = listOf(ProxyRoute(method = "POST", path = "/up_next/list")),
    )
    assertThat(
      IssueCollector.collect {
        AppManifestChecker().check(
          manifest.copy(
            proxy = listOf(
              proxy,
              proxy.copy(name = "feeds", route = listOf(ProxyRoute("GET", "/feeds/**"))),
            ),
          ),
        )
      },
    ).containsExactly()

    assertThat(manifest.copy(proxy = listOf(proxy.copy(origin = "http://api.example.com"))))
      .failsValidation(
        message = "origin must be https://host or https://host:port, with no path",
        href = "proxy[0].origin",
      )
    assertThat(manifest.copy(proxy = listOf(proxy.copy(origin = "https://api.example.com/v1"))))
      .failsValidation(
        message = "origin must be https://host or https://host:port, with no path",
        href = "proxy[0].origin",
      )
    assertThat(manifest.copy(proxy = listOf(proxy.copy(request_headers = listOf("Cookie")))))
      .failsValidation(
        message = "header 'Cookie' can't be forwarded",
        href = "proxy[0].request_headers[0]",
      )
    assertThat(manifest.copy(proxy = listOf(proxy.copy(route = listOf()))))
      .failsValidation(
        message = "a proxy must declare at least one route",
        href = "proxy[0].route",
      )
    assertThat(manifest.copy(proxy = listOf(proxy.copy(route = listOf(ProxyRoute("CONNECT", "/"))))))
      .failsValidation(
        message = "method must be one of $ProxyMethods",
        href = "proxy[0].route[0].method",
      )
    assertThat(manifest.copy(proxy = listOf(proxy.copy(route = listOf(ProxyRoute("GET", "/a/*/b"))))))
      .failsValidation(
        message = "string may not contain '*', except in a wildcard at the end",
        href = "proxy[0].route[0].path",
      )
    assertThat(manifest.copy(proxy = listOf(proxy.copy(route = listOf(ProxyRoute("GET", "/a/../b"))))))
      .failsValidation(
        message = "path must not contain '.' or '..' segments",
        href = "proxy[0].route[0].path",
      )
    assertThat(manifest.copy(proxy = listOf(proxy, proxy)))
      .failsValidation(
        message = "proxy names must be unique",
        href = "proxy",
      )
  }

  @Test
  fun decodeProxy() {
    val decoded = WasmoToml.decodeFromString(
      AppManifest.serializer(),
      """
      |target = 'https://wasmo.com/sdk/1'
      |version = 1
      |
      |[[proxy]]
      |name = 'podcasts'
      |origin = 'https://api.example.com'
      |request_headers = ['Authorization']
      |
      |[[proxy.route]]
      |method = 'POST'
      |path = '/up_next/list'
      |
      |[[proxy.route]]
      |method = 'GET'
      |path = '/feeds/**'
      """.trimMargin(),
    )
    assertThat(decoded.proxy).containsExactly(
      Proxy(
        name = "podcasts",
        origin = "https://api.example.com",
        request_headers = listOf("Authorization"),
        route = listOf(ProxyRoute("POST", "/up_next/list"), ProxyRoute("GET", "/feeds/**")),
      ),
    )
  }
}

fun Assert<AppManifest>.failsValidation(
  href: String,
  message: String,
  appManifestChecker: AppManifestChecker = AppManifestChecker(),
) {
  transform { manifest ->
    IssueCollector.collect {
      appManifestChecker.check(manifest)
    }
  }.containsExactly(Issue(href = href, message = message))
}
