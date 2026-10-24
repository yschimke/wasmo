package com.wasmo.client.app

import androidx.compose.runtime.remember
import com.wasmo.api.WasmoJson
import com.wasmo.api.routes.RoutingContext
import com.wasmo.client.app.routing.Router
import com.wasmo.client.app.ui.UiFactory
import com.wasmo.common.logging.ConsoleLogger
import com.wasmo.common.logging.Logger
import com.wasmo.framework.detectPageData
import com.wasmo.framework.registerTopPrivateDomain
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.createGraphFactory
import org.jetbrains.compose.web.renderComposableInBody

@Inject
class WasmoWebApp(
  private val environment: Environment,
  private val router: Router,
  private val uiFactory: UiFactory,
) {
  fun start() {
    router.start()
    renderComposableInBody {
      val route = router.current.value ?: return@renderComposableInBody
      val ui = remember(route) { uiFactory.create(route) }

      EnvironmentFrame(environment) { attrs ->
        ui.Show(attrs = attrs)
      }
    }
  }
}

fun createWasmoWebApp(
  logger: Logger = ConsoleLogger,
  environment: Environment,
): WasmoWebApp {
  val pageData = detectPageData(WasmoJson)

  // Teach URL decoding this deployment's root host before the router decodes the current location,
  // so a computer or app host like kodu.<host> resolves its subdomain instead of rendering blank.
  pageData.get<RoutingContext>("routing_context")?.let {
    val root = it.root
    val rootHost =
      root.subdomain?.let { sub -> "$sub.${root.topPrivateDomain}" } ?: root.topPrivateDomain
    registerTopPrivateDomain(rootHost)
  }

  val wasmoWebAppGraphFactory = createGraphFactory<WasmoWebAppGraph.Factory>()
  val wasmoWebAppGraph = wasmoWebAppGraphFactory.create(
    logger = logger,
    environment = environment,
    pageData = pageData,
  )
  return wasmoWebAppGraph.wasmoWebApp
}
