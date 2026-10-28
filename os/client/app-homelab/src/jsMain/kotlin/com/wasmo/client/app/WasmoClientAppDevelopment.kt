package com.wasmo.client.app

import kotlinx.browser.window

@JsExport
fun startOnLoad() {
  window.onload = {
    val app = createWasmoWebApp(
      environment = DevelopmentEnvironment,
    )
    app.start()
  }
}

object DevelopmentEnvironment : Environment {
  override val passkeyUser: String
    get() = "wasmo-development-passkeys"

  // Homelab also serves real domains; only flag the local development host as such.
  override val warningLabel: String?
    get() = window.location.hostname
      .takeIf { it == "wasmo.localhost" || it.endsWith(".wasmo.localhost") }
      ?.let { "wasmo.localhost" }

  override val showSignUp: Boolean
    get() = true
}
