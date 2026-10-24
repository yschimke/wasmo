package com.wasmo.common.routes

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import com.wasmo.api.routes.AppRoute
import com.wasmo.api.routes.ComputerHomeRoute
import com.wasmo.api.routes.RoutingContext
import com.wasmo.framework.decodeUrl
import com.wasmo.framework.registerTopPrivateDomain
import com.wasmo.identifiers.AppSlug
import com.wasmo.identifiers.ComputerSlug
import kotlin.test.Test

/**
 * A deployment on a subdomain base URL (e.g. `https://wasmo.example.co.uk/`) must route its
 * computer and app hosts correctly. Before the fix, [decodeUrl] gave these hosts subdomain `null`,
 * so [RealRouteCodec.decode] returned the OS home route — a blank client page, and a 500
 * (`HomeRoute cannot be cast to AppRoute`) for an app request on the server.
 */
class RouteDecodeOnCustomRootTest {
  private val routeCodec = RealRouteCodec(
    routingContext = RoutingContext(rootUrl = "https://wasmo.example.co.uk/"),
  )

  init {
    registerTopPrivateDomain("wasmo.example.co.uk")
  }

  @Test
  fun appHostDecodesToAppRoute() {
    val route = routeCodec.decode("https://smart-kodu.wasmo.example.co.uk/".decodeUrl())

    assertThat(route).isInstanceOf(AppRoute::class)
    route as AppRoute
    assertThat(route.appSlug).isEqualTo(AppSlug("smart"))
    assertThat(route.computerSlug).isEqualTo(ComputerSlug("kodu"))
  }

  @Test
  fun computerHostDecodesToComputerHomeRoute() {
    val route = routeCodec.decode("https://kodu.wasmo.example.co.uk/".decodeUrl())

    assertThat(route).isEqualTo(ComputerHomeRoute(ComputerSlug("kodu")))
  }
}
