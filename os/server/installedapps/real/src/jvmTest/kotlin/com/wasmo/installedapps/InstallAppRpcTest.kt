package com.wasmo.installedapps

import app.cash.burst.InterceptTest
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import com.wasmo.api.InstalledAppSnapshot
import com.wasmo.framework.Response
import com.wasmo.identifiers.WasmoFileAddress.Companion.toWasmoFileAddress
import com.wasmo.support.issues.Issue
import com.wasmo.testing.apps.RecipesApp
import com.wasmo.testing.events.AfterInstallEvent
import com.wasmo.testing.framework.ResponseBodySnapshot
import com.wasmo.testing.framework.resourceHeaders
import com.wasmo.testing.service.ServiceTester
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okio.ByteString.Companion.encodeUtf8
import wasmo.http.FakeHttpService
import wasmo.http.HttpResponse
import wasmo.objectstore.GetObjectRequest
import wasmo.objectstore.PutObjectRequest

class InstallAppRpcTest {
  @InterceptTest
  val tester = ServiceTester()

  @Test
  fun happyPath() = runTest {
    val app = tester.sampleApps.recipes.publishedApp
    tester.publishApp(app)

    val client = tester.newClient()
    val computer = client.createComputer()
    val installedApp = computer.installApp(
      publishedApp = app,
    )

    val appWasmKey = "${computer.slug}/${installedApp.slug}/resources/v1/app.wasm"
    assertThat(tester.objectStore[appWasmKey]).isEqualTo(installedApp.publishedApp.wasm)

    assertThat(computer.homePage().computerSnapshot?.apps)
      .isNotNull()
      .contains(
        InstalledAppSnapshot(
          slug = installedApp.slug,
          launcherLabel = "Recipes",
          maskableIconUrl = "https://recipes-${computer.slug}.wasmo.com/maskable-icon.svg",
          homeUrl = "https://recipes-${computer.slug}.wasmo.com/home",
        ),
      )

    assertThat(tester.eventListener.receive<InstallAppEvent>())
      .isEqualTo(
        InstallAppEvent(
          computerSlug = computer.slug,
          appSlug = installedApp.slug,
        ),
      )

    assertThat(installedApp.call("/"))
      .isEqualTo(
        Response(
          headers = resourceHeaders("Welcome to the recipes app"),
          contentType = "text/html".toMediaType(),
          body = ResponseBodySnapshot("Welcome to the recipes app"),
        ),
      )
  }

  @Test
  fun installOverOlderVersionRetainsData() = runTest {
    val app = tester.sampleApps.recipes.publishedApp
    tester.publishApp(app)
    val computer = tester.newClient().createComputer()
    val installed = computer.installApp(app)
    tester.eventListener.receive<AfterInstallEvent>()
    val platform = (installed.load() as RecipesApp).platform
    platform.objectStore.put(PutObjectRequest("settings.json", "saved settings".encodeUtf8()))
    platform.sqlService.getOrCreate().newConnection().use { connection ->
      connection.execute("CREATE TABLE SavedSettings (value TEXT)")
      connection.execute("INSERT INTO SavedSettings VALUES ('saved settings')")
    }

    val updated = app.copy(
      wasmoFileAddress = "https://example.com/recipes/v2/recipes.wasmo".toWasmoFileAddress(),
      appManifest = app.appManifest.copy(version = 2L),
      resources = app.resources + ("www/index.html" to "Updated recipes".encodeUtf8()),
    )
    tester.publishApp(updated)
    val reinstalled = computer.installApp(updated)

    assertThat(reinstalled.call("/").body).isEqualTo(ResponseBodySnapshot("Updated recipes"))
    assertThat(tester.eventListener.receive<AfterInstallEvent>()).isEqualTo(
      AfterInstallEvent(app.slug, 1L, 2L),
    )
    assertThat(computer.homePage().computerSnapshot!!.apps.count { it.slug == app.slug })
      .isEqualTo(1)
    val updatedPlatform = (reinstalled.load() as RecipesApp).platform
    assertThat(updatedPlatform.objectStore.get(GetObjectRequest("settings.json")).value)
      .isEqualTo("saved settings".encodeUtf8())
    updatedPlatform.sqlService.getOrCreate().newConnection().use { connection ->
      connection.executeQuery("SELECT value FROM SavedSettings").use { rows ->
        assertThat(rows.next()!!.getString(0)).isEqualTo("saved settings")
      }
    }
  }

  @Test
  fun reinstallSameArchive() = runTest {
    val app = tester.sampleApps.recipes.publishedApp
    tester.publishApp(app)
    val computer = tester.newClient().createComputer()
    computer.installApp(app)
    val reinstalled = computer.installApp(app)
    assertThat(reinstalled.call("/").body)
      .isEqualTo(ResponseBodySnapshot("Welcome to the recipes app"))
    assertThat(computer.homePage().computerSnapshot!!.apps.count { it.slug == app.slug })
      .isEqualTo(1)
  }

  @Test
  fun failedUpgradeKeepsPreviousReleaseAndCanRetry() = runTest {
    val app = tester.sampleApps.recipes.publishedApp
    tester.publishApp(app)
    val computer = tester.newClient().createComputer()
    val installed = computer.installApp(app)
    tester.eventListener.receive<InstallAppEvent>()
    val updated = app.copy(
      wasmoFileAddress = "https://example.com/recipes/v2/recipes.wasmo".toWasmoFileAddress(),
      appManifest = app.appManifest.copy(version = 2L),
      resources = app.resources + ("www/index.html" to "Updated recipes".encodeUtf8()),
    )

    computer.installApp(updated)
    assertThat(tester.eventListener.receive<InstallAppEvent>().issues)
      .containsExactly(
        Issue(url = updated.wasmoFileAddress.toString(), message = "HTTP request failed: 404"),
      )
    assertThat(installed.call("/").body)
      .isEqualTo(ResponseBodySnapshot("Welcome to the recipes app"))

    tester.publishApp(updated)
    assertThat(computer.installApp(updated).call("/").body)
      .isEqualTo(ResponseBodySnapshot("Updated recipes"))
  }

  @Test
  fun failedFirstInstallCanRetry() = runTest {
    val app = tester.sampleApps.recipes.publishedApp
    val computer = tester.newClient().createComputer()
    computer.installApp(app)
    tester.publishApp(app)
    assertThat(computer.installApp(app).call("/").body)
      .isEqualTo(ResponseBodySnapshot("Welcome to the recipes app"))
  }

  @Test
  fun wasmoFileIsAbsent() = runTest {
    val app = tester.sampleApps.recipes.publishedApp
    val client = tester.newClient()
    val computer = client.createComputer()
    computer.installApp(app)

    assertThat(tester.eventListener.receive<InstallAppEvent>().issues)
      .containsExactly(
        Issue(
          url = app.wasmoFileAddress.toString(),
          message = "HTTP request failed: 404",
        ),
      )
  }

  @Test
  fun wasmoFileIsMalformedZip() = runTest {
    val app = tester.sampleApps.recipes.publishedApp
    tester.fakeHttpClient += FakeHttpService.Handler {
      HttpResponse(
        body = "this is not a .wasmo file".encodeUtf8(),
      )
    }

    val client = tester.newClient()
    val computer = client.createComputer()
    computer.installApp(app)

    assertThat(tester.eventListener.receive<InstallAppEvent>().issues)
      .containsExactly(
        Issue(
          url = app.wasmoFileAddress.toString(),
          message = "No wasmo-manifest.toml file in .wasmo archive",
        ),
      )
  }
}
