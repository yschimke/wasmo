package com.wasmo.client.app.routing

import com.wasmo.api.routes.HomeRoute
import com.wasmo.api.routes.RoutingContext
import com.wasmo.api.routes.SignInRoute
import com.wasmo.client.app.browser.Browser
import com.wasmo.common.routes.RealRouteCodec
import com.wasmo.framework.Url
import com.wasmo.framework.encode
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import org.w3c.dom.PopStateEvent

class RouterTest {
  private val routeCodec = RealRouteCodec(
    routingContext = RoutingContext(rootUrl = "https://wasmo.com/"),
  )
  private val browser = FakeBrowser()
  private val router = Router(
    scope = CoroutineScope(EmptyCoroutineContext),
    routeCodec = routeCodec,
    browser = browser,
  )

  /**
   * Regression test for the post-sign-in crash. A page served signed out carries no
   * `computer_list_snapshot` in pageData, so sending the user Home in-app builds HomeUi with an
   * absent snapshot and throws in composition. After sign-in the presenter must instead
   * [Router.reload] Home, forcing a full page load so the server re-renders the signed-in page with
   * the snapshot present.
   */
  @Test
  fun reloadForcesFullPageLoadSoServerRerendersPageData() {
    // The page the user signed in on.
    browser.locationHref = routeCodec.encode(SignInRoute).encode()

    router.reload(HomeRoute)

    // A full load to the home URL: in a real browser this unloads the page, so no client-side
    // HomeUi is ever constructed with the missing snapshot.
    assertEquals(routeCodec.encode(HomeRoute).encode(), browser.locationHref)
    assertEquals(0, browser.pushStateCount)
  }

  /** Ordinary navigation stays an in-app history push — only the sign-in boundary forces a load. */
  @Test
  fun goToKeepsOrdinaryNavigationInApp() {
    val startUrl = routeCodec.encode(HomeRoute).encode()
    browser.locationHref = startUrl

    router.goTo(SignInRoute, TransitionDirection.PUSH)

    assertEquals(startUrl, browser.locationHref)
    assertEquals(1, browser.pushStateCount)
    assertEquals(SignInRoute, router.current.value)
  }
}

private class FakeBrowser : Browser {
  override var locationHref: String = ""
  override var onpopstate: ((PopStateEvent) -> dynamic)? = null
  var pushStateCount = 0
    private set

  override fun back() {
  }

  override fun pushState(data: Any?, title: String, url: Url?) {
    pushStateCount++
  }

  override fun replaceState(data: Any?, title: String, url: Url?) {
  }
}
