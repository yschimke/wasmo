package com.wasmo.computers

import app.cash.burst.InterceptTest
import assertk.assertThat
import assertk.assertions.isEqualTo
import com.wasmo.api.RemoveAppRequest
import com.wasmo.api.WasmoJson
import com.wasmo.db.computers.selectComputer
import com.wasmo.db.installedapps.selectInstalledAppByComputerIdAndSlug
import com.wasmo.framework.ArgumentUserException
import com.wasmo.framework.NotFoundUserException
import com.wasmo.framework.Request
import com.wasmo.framework.toWasmoUrl
import com.wasmo.identifiers.AppSlug
import com.wasmo.identifiers.ComputerSlug
import com.wasmo.testing.apps.RecipesApp
import com.wasmo.testing.service.ServiceTester
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.ByteString.Companion.encodeUtf8
import wasmo.objectstore.GetObjectRequest
import wasmo.objectstore.PutObjectRequest
import wasmox.sql.transaction
import wit.wasmo.http.v0_1_0.Header

class RemoveAppTest {
  @InterceptTest val tester = ServiceTester()

  @Test
  fun removesOnlyTheSelectedInstallAndDisablesItsUrl() = runTest {
    val app = tester.sampleApps.recipes.publishedApp
    tester.publishApp(app)
    val client = tester.newClient()
    val computer = client.createComputer()
    val installed = computer.installApp(app)
    val duplicate = computer.installApp(app.copy(slug = AppSlug("recipes2")))
    val before = computer.homePage().computerSnapshot!!

    val response = client.call().removeApp(computer.slug, RemoveAppRequest(app.slug))

    assertThat(response.body.computer.apps).isEqualTo(before.apps.filter { it.slug != app.slug })
    assertThat(computer.homePage().computerSnapshot).isEqualTo(response.body.computer)
    assertFailsWith<NotFoundUserException> { installed.call("/") }
    assertThat(duplicate.call("/").body)
      .isEqualTo(com.wasmo.testing.framework.ResponseBodySnapshot("Welcome to the recipes app"))
  }

  @Test
  fun httpEndpointRequiresSameOriginJsonPost() = runTest {
    val app = tester.sampleApps.recipes.publishedApp
    tester.publishApp(app)
    val client = tester.newClient()
    val computer = client.createComputer()
    computer.installApp(app)
    val before = computer.homePage().computerSnapshot
    val origin = "https://${computer.slug}.wasmo.com"
    val request =
      Request(
        method = "POST",
        url = "$origin/remove-app".toHttpUrl(),
        headers = listOf(Header("Origin", origin), Header("Content-Type", "application/json")),
        body = WasmoJson.encodeToString(RemoveAppRequest(app.slug)).encodeUtf8(),
      )
    for (invalid in
      listOf(
        request.copy(method = "GET"),
        request.copy(headers = listOf(Header("Content-Type", "application/json"))),
        request.copy(
          headers =
            listOf(
              Header("Origin", "https://evil.wasmo.com"),
              Header("Content-Type", "application/json"),
            )
        ),
        request.copy(
          headers = listOf(Header("Origin", origin), Header("Content-Type", "text/plain"))
        ),
        request.copy(body = "invalid json".encodeUtf8()),
      )) {
      assertFailsWith<ArgumentUserException> { client.call().removeAppRequest(invalid) }
      assertThat(computer.homePage().computerSnapshot).isEqualTo(before)
    }
    assertThat(client.call().removeAppRequest(request).status).isEqualTo(200)
    assertThat(computer.homePage().computerSnapshot!!.apps.any { it.slug == app.slug })
      .isEqualTo(false)
  }

  @Test
  fun acceptsPublicHttpsOriginBehindATlsTerminatingProxy() = runTest {
    val app = tester.sampleApps.recipes.publishedApp
    tester.publishApp(app)
    val client = tester.newClient()
    val computer = client.createComputer()
    computer.installApp(app)
    val publicUrl = "https://${computer.slug}.wasmo.com/remove-app".toHttpUrl()
    val response =
      client
        .call()
        .removeAppRequest(
          request =
            Request(
              method = "POST",
              url = "http://${computer.slug}.wasmo.com:54400/remove-app".toHttpUrl(),
              headers =
                listOf(
                  Header("Origin", "https://${computer.slug}.wasmo.com"),
                  Header("Content-Type", "application/json"),
                ),
              body = WasmoJson.encodeToString(RemoveAppRequest(app.slug)).encodeUtf8(),
            ),
          url = publicUrl.toWasmoUrl(),
        )
    assertThat(response.status).isEqualTo(200)
    assertThat(computer.homePage().computerSnapshot!!.apps.any { it.slug == app.slug })
      .isEqualTo(false)
  }

  @Test
  fun repeatedRemovalAndMissingAppAreHarmless() = runTest {
    val app = tester.sampleApps.recipes.publishedApp
    tester.publishApp(app)
    val client = tester.newClient()
    val computer = client.createComputer()
    computer.installApp(app)
    val first = client.call().removeApp(computer.slug, RemoveAppRequest(app.slug))
    assertThat(client.call().removeApp(computer.slug, RemoveAppRequest(app.slug))).isEqualTo(first)
    assertThat(client.call().removeApp(computer.slug, RemoveAppRequest(AppSlug("missing"))))
      .isEqualTo(first)
  }

  @Test
  fun anotherUserAndSignedOutClientCannotRemoveApps() = runTest {
    val app = tester.sampleApps.recipes.publishedApp
    tester.publishApp(app)
    val owner = tester.newClient()
    val computer = owner.createComputer()
    computer.installApp(app)
    val before = computer.homePage().computerSnapshot
    val other = tester.newClient()
    other.createComputer(ComputerSlug("other"))
    for (client in listOf(other, tester.newClient())) {
      assertFailsWith<NotFoundUserException> {
        client.call().removeApp(computer.slug, RemoveAppRequest(app.slug))
      }
    }
    assertThat(computer.homePage().computerSnapshot).isEqualTo(before)
  }

  @Test
  fun removalIsScopedToOneComputer() = runTest {
    val app = tester.sampleApps.recipes.publishedApp
    tester.publishApp(app)
    val client = tester.newClient()
    val first = client.createComputer()
    val second = client.createComputer()
    first.installApp(app)
    val remaining = second.installApp(app)
    val before = second.homePage().computerSnapshot
    client.call().removeApp(first.slug, RemoveAppRequest(app.slug))
    assertThat(second.homePage().computerSnapshot).isEqualTo(before)
    remaining.call("/")
  }

  @Test
  fun reinstallReusesStoredFilesSqlAndInstallationRecord() = runTest {
    val app = tester.sampleApps.recipes.publishedApp
    tester.publishApp(app)
    val client = tester.newClient()
    val computer = client.createComputer()
    val installed = computer.installApp(app)
    val platform = (installed.load() as RecipesApp).platform
    platform.objectStore.put(PutObjectRequest("settings.json", "saved".encodeUtf8()))
    platform.sqlService.getOrCreate().newConnection().use { connection ->
      connection.execute("CREATE TABLE SavedSettings (value TEXT)")
      connection.execute("INSERT INTO SavedSettings VALUES ('saved')")
    }
    val installedId =
      tester.wasmoDb.transaction {
        selectInstalledAppByComputerIdAndSlug(selectComputer(computer.slug)!!.id, app.slug, true)!!
          .installedApp
          .id
      }

    repeat(2) {
      client.call().removeApp(computer.slug, RemoveAppRequest(app.slug))
      val reinstalled = computer.installApp(app)
      val restored = (reinstalled.load() as RecipesApp).platform
      assertThat(restored.objectStore.get(GetObjectRequest("settings.json")).value)
        .isEqualTo("saved".encodeUtf8())
      restored.sqlService.getOrCreate().newConnection().use { connection ->
        connection.executeQuery("SELECT value FROM SavedSettings").use { rows ->
          assertThat(rows.next()!!.getString(0)).isEqualTo("saved")
        }
      }
      tester.wasmoDb.transaction {
        assertThat(
            selectInstalledAppByComputerIdAndSlug(
                selectComputer(computer.slug)!!.id,
                app.slug,
                true,
              )!!
              .installedApp
              .id
          )
          .isEqualTo(installedId)
      }
    }
  }
}
