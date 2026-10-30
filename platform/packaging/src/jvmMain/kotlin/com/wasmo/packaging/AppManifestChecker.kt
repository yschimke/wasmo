package com.wasmo.packaging

import com.wasmo.support.issues.IssueCollector
import com.wasmo.support.issues.issueCheck

/**
 * Validates a manifest against our spec.
 *
 * We do validation here and not in `TOML` parsing because we want to aggregate all errors before
 * reporting any errors.
 *
 * We also want to exactly identify where the problems are, such as `route[3].resource_path` which
 * isn't possible in simple constructor argument checks.
 */
class AppManifestChecker(
  val allowExternalResources: Boolean = false,
) {
  context(issueCollector: IssueCollector)
  fun check(manifest: AppManifest) {
    context(issueCollector.href("target")) {
      issueCheck(manifest.target in SupportedTargets) {
        """
        |unsupported target '${manifest.target}'
        |expected one of $SupportedTargets
        """.trimMargin()
      }
    }

    context(issueCollector.href("version")) {
      issueCheck(manifest.version >= 1) {
        """
        |unexpected version ${manifest.version}
        |expected a positive integer
        """.trimMargin()
      }
    }

    if (allowExternalResources) {
      for ((index, resource) in manifest.external_resource.withIndex()) {
        context(issueCollector.href("external_resource[$index]")) {
          check(resource)
        }
      }
    } else {
      context(issueCollector.href("external_resource")) {
        issueCheck(manifest.external_resource.isEmpty()) {
          "external resources are not permitted for this manifest"
        }
      }
    }

    val launcher = manifest.launcher
    if (launcher != null) {
      context(issueCollector.href("launcher")) {
        check(launcher)
      }
    }

    for ((index, proxy) in manifest.proxy.withIndex()) {
      context(issueCollector.href("proxy[$index]")) {
        check(proxy)
      }
    }
    context(issueCollector.href("proxy")) {
      val names = manifest.proxy.map { it.name }
      issueCheck(names.size == names.toSet().size) {
        "proxy names must be unique"
      }
    }
  }

  context(issueCollector: IssueCollector)
  private fun check(proxy: Proxy) {
    context(issueCollector.href("name")) {
      issueCheck(ProxyName.matches(proxy.name)) {
        "name must match ${ProxyName.pattern}"
      }
    }

    context(issueCollector.href("origin")) {
      issueCheck(ProxyOrigin.matches(proxy.origin)) {
        "origin must be https://host or https://host:port, with no path"
      }
    }

    for ((index, header) in proxy.request_headers.withIndex()) {
      context(issueCollector.href("request_headers[$index]")) {
        issueCheck(HeaderName.matches(header)) {
          "invalid header name '$header'"
        }
        issueCheck(header.lowercase() !in UnforwardableHeaders) {
          "header '$header' can't be forwarded"
        }
      }
    }

    context(issueCollector.href("route")) {
      issueCheck(proxy.route.isNotEmpty()) {
        "a proxy must declare at least one route"
      }
    }
    for ((index, route) in proxy.route.withIndex()) {
      context(issueCollector.href("route[$index]")) {
        check(route)
      }
    }
  }

  context(issueCollector: IssueCollector)
  private fun check(route: ProxyRoute) {
    context(issueCollector.href("method")) {
      issueCheck(route.method in ProxyMethods) {
        "method must be one of $ProxyMethods"
      }
    }
    context(issueCollector.href("path")) {
      checkPath(path = route.path, allowTrailingWildcard = true)
      issueCheck(route.path.split("/").none { it == "." || it == ".." }) {
        "path must not contain '.' or '..' segments"
      }
    }
  }

  context(issueCollector: IssueCollector)
  private fun check(externalResource: ExternalResource) {
    context(issueCollector.href("to")) {
      issueCheck(".." !in externalResource.to.split("/")) {
        "target directory must not contain '..' path traversal operators"
      }

      issueCheck(externalResource.to.startsWith("/")) {
        "target directory must start with '/'"
      }

      for ((index, include) in externalResource.include.withIndex()) {
        context(issueCollector.href("include[$index]")) {
          issueCheck(!include.startsWith("/")) {
            "include must not start with '/'"
          }
        }
      }
    }
  }

  context(issueCollector: IssueCollector)
  private fun check(launcher: Launcher) {
    val maskableIconPath = launcher.maskable_icon_path
    if (maskableIconPath != null) {
      context(issueCollector.href("maskable_icon_path")) {
        checkPath(
          path = maskableIconPath,
        )
      }
    }

    val homePath = launcher.home_path
    if (homePath != null) {
      context(issueCollector.href("home_path")) {
        checkPath(
          path = homePath,
        )
      }
    }
  }

  context(issueCollector: IssueCollector)
  private fun checkPath(
    path: String,
    allowTrailingWildcard: Boolean = false,
    requireTrailingWildcard: Boolean = false,
  ) {
    issueCheck(path.startsWith("/")) {
      "string must start with /"
    }

    issueCheck(!path.startsWith("//")) {
      "string must not start with //"
    }

    issueCheck(!requireTrailingWildcard || path.endsWith("/**")) {
      "string must end with '/**'"
    }

    if (allowTrailingWildcard) {
      issueCheck("*" !in path.removeSuffix("/**")) {
        "string may not contain '*', except in a wildcard at the end"
      }
    } else {
      issueCheck("*" !in path) {
        "string may not contain '*'"
      }
    }
  }
}

const val TargetSdk1 = "https://wasmo.com/sdk/1"
internal val SupportedTargets = setOf(TargetSdk1)
internal val SupportedAccessValues = setOf(
  "public",
  "private",
)

internal val ProxyName = Regex("[a-z][a-z0-9-]{0,31}")
internal val ProxyOrigin = Regex("https://[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+(:[1-9][0-9]{0,4})?")
internal val ProxyMethods = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE")
private val HeaderName = Regex("[A-Za-z0-9][A-Za-z0-9-]{0,63}")
// The host sets these itself, or they would let an app speak for the owner's browser.
internal val UnforwardableHeaders = setOf(
  "connection", "content-length", "cookie", "expect", "forwarded", "host", "keep-alive", "origin",
  "proxy-authorization", "proxy-connection", "referer", "te", "trailer", "transfer-encoding",
  "upgrade", "x-forwarded-for", "x-forwarded-host", "x-forwarded-proto", "x-real-ip",
  "x-wasmo-api",
)
