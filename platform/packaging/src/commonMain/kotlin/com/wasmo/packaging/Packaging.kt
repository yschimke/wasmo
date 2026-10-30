package com.wasmo.packaging

import kotlinx.serialization.Serializable

@Serializable
data class AppManifest(
  val target: String,
  val version: Long,
  val external_resource: List<ExternalResource> = listOf(),
  val launcher: Launcher? = null,
  val proxy: List<Proxy> = listOf(),
)

@Serializable
data class ExternalResource(
  val from: String,
  val to: String,
  val include: List<String> = listOf(),
)

@Serializable
data class Launcher(
  val label: String? = null,
  val maskable_icon_path: String? = null,
  val home_path: String? = null,
)

/**
 * A web origin the app's browser code may reach through its install, for APIs that refuse
 * cross-origin requests. Only the declared routes are forwarded, and only once the operator has
 * approved the origin for that install.
 */
@Serializable
data class Proxy(
  val name: String,
  val origin: String,
  val request_headers: List<String> = listOf(),
  val route: List<ProxyRoute> = listOf(),
)

@Serializable
data class ProxyRoute(
  val method: String,
  val path: String,
)
