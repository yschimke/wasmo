package com.wasmo.client.app.computer

import com.wasmo.api.ComputerSnapshot
import com.wasmo.api.InstalledAppSnapshot
import com.wasmo.identifiers.AppSlug
import com.wasmo.identifiers.ComputerSlug
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.browser.document
import org.jetbrains.compose.web.renderComposableInBody
import org.w3c.dom.HTMLButtonElement

class ComputerRemovalTest {
  private val snapshot =
    ComputerSnapshot(
      slug = ComputerSlug("yuri"),
      apps =
        listOf("terrazzo", "terrazzo2").map { slug ->
          InstalledAppSnapshot(AppSlug(slug), "Terrazzo", "/assets/launcher/sample-icon.svg", "/")
        },
    )

  @Test
  fun removeTargetsTheInstallNameWithoutLaunchingTheApp() {
    val events = mutableListOf<ComputerEvent>()
    val composition = renderComposableInBody {
      Computer(scrimVisible = false, snapshot = snapshot, eventListener = events::add)
    }
    try {
      val remove =
        document.querySelector("button[aria-label='Remove terrazzo2']") as HTMLButtonElement
      remove.click()
      assertEquals(
        listOf<ComputerEvent>(ComputerEvent.ClickRemoveApp(AppSlug("terrazzo2"))),
        events,
      )
    } finally {
      composition.dispose()
    }
  }

  @Test
  fun removalDisablesButtonsUntilTheRequestCompletes() {
    val events = mutableListOf<ComputerEvent>()
    val composition = renderComposableInBody {
      Computer(
        scrimVisible = false,
        snapshot = snapshot,
        removingApp = AppSlug("terrazzo2"),
        eventListener = events::add,
      )
    }
    try {
      for (slug in listOf("terrazzo", "terrazzo2")) {
        val button =
          document.querySelector("button[aria-label='Remove $slug']") as HTMLButtonElement
        assertTrue(button.disabled)
        button.click()
      }
      assertTrue(events.isEmpty())
      assertEquals(
        "true",
        document.querySelector("button[aria-label='Remove terrazzo2']")!!.getAttribute("aria-busy"),
      )
    } finally {
      composition.dispose()
    }
  }
}
