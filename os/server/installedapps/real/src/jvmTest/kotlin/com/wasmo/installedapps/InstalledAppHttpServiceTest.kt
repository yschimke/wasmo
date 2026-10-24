package com.wasmo.installedapps

import app.cash.burst.InterceptTest
import assertk.assertThat
import assertk.assertions.isEqualTo
import com.wasmo.framework.NotFoundUserException
import com.wasmo.framework.Response
import com.wasmo.testing.apps.RecipesApp
import com.wasmo.testing.framework.ResponseBodySnapshot
import com.wasmo.testing.service.ServiceTester
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okio.ByteString.Companion.encodeUtf8
import wasmo.objectstore.PutObjectRequest
import wit.wasmo.http.v0_1_0.Header

class InstalledAppHttpServiceTest {
  @InterceptTest
  val tester = ServiceTester()

  // The headers a static response carries: a strong ETag (SHA-256 of the body) and Cache-Control.
  private fun cacheHeaders(body: String) = listOf(
    Header("ETag", "\"${body.encodeUtf8().sha256().hex()}\""),
    Header("Cache-Control", "no-cache"),
  )

  @Test
  fun resourceInWww() = runTest {
    val app = tester.sampleApps.recipes.publishedApp.copy(
      resources = tester.sampleApps.recipes.publishedApp.resources + mapOf(
        "www/resource1.txt" to "This is a resource in www".encodeUtf8(),
      ),
    )
    tester.publishApp(app)

    val owner = tester.newClient()
    val computer = owner.createComputer()
    val installedApp = computer.installApp(app)

    assertThat(owner.call().callApp(url = installedApp.url.resolve("/resource1.txt")!!))
      .isEqualTo(
        Response(
          headers = cacheHeaders("This is a resource in www"),
          contentType = "text/plain".toMediaType(),
          body = ResponseBodySnapshot("This is a resource in www"),
        ),
      )

    val anotherUser = tester.newClient()
    assertFailsWith<NotFoundUserException> {
      anotherUser.call().callApp(url = installedApp.url.resolve("/resource1.txt")!!)
    }
  }

  @Test
  fun resourceInWwwPublic() = runTest {
    val app = tester.sampleApps.recipes.publishedApp.copy(
      resources = tester.sampleApps.recipes.publishedApp.resources + mapOf(
        "www-public/resource1.txt" to "This is a resource in www-public".encodeUtf8(),
      ),
    )
    tester.publishApp(app)

    val owner = tester.newClient()
    val computer = owner.createComputer()
    val installedApp = computer.installApp(app)

    assertThat(owner.call().callApp(url = installedApp.url.resolve("/resource1.txt")!!))
      .isEqualTo(
        Response(
          headers = cacheHeaders("This is a resource in www-public"),
          contentType = "text/plain".toMediaType(),
          body = ResponseBodySnapshot("This is a resource in www-public"),
        ),
      )

    val anotherUser = tester.newClient()
    assertThat(anotherUser.call().callApp(url = installedApp.url.resolve("/resource1.txt")!!))
      .isEqualTo(
        Response(
          headers = cacheHeaders("This is a resource in www-public"),
          contentType = "text/plain".toMediaType(),
          body = ResponseBodySnapshot("This is a resource in www-public"),
        ),
      )
  }

  @Test
  fun resourceInWwwPublicTakesPrecedenceOverResourceInWww() = runTest {
    val app = tester.sampleApps.recipes.publishedApp.copy(
      resources = tester.sampleApps.recipes.publishedApp.resources + mapOf(
        "www/resource1.txt" to "This is a resource in www".encodeUtf8(),
        "www-public/resource1.txt" to "This is a resource in www-public".encodeUtf8(),
      ),
    )
    tester.publishApp(app)

    val client = tester.newClient()
    val computer = client.createComputer()
    val installedApp = computer.installApp(app)

    assertThat(installedApp.call("/resource1.txt"))
      .isEqualTo(
        Response(
          headers = cacheHeaders("This is a resource in www-public"),
          contentType = "text/plain".toMediaType(),
          body = ResponseBodySnapshot("This is a resource in www-public"),
        ),
      )
  }

  @Test
  fun objectInWww() = runTest {
    val app = tester.sampleApps.recipes.publishedApp
    tester.publishApp(app)

    val owner = tester.newClient()
    val computer = owner.createComputer()
    val installedApp = computer.installApp(app)

    val recipesApp = installedApp.load() as RecipesApp
    recipesApp.platform.objectStore.put(
      PutObjectRequest(
        key = "www/resource1.txt",
        value = "This is an object in www".encodeUtf8(),
        contentType = "text/sample",
      ),
    )

    assertThat(owner.call().callApp(url = installedApp.url.resolve("/resource1.txt")!!))
      .isEqualTo(
        Response(
          headers = cacheHeaders("This is an object in www"),
          contentType = "text/sample".toMediaType(),
          body = ResponseBodySnapshot("This is an object in www"),
        ),
      )

    val anotherUser = tester.newClient()
    assertFailsWith<NotFoundUserException> {
      anotherUser.call().callApp(url = installedApp.url.resolve("/resource1.txt")!!)
    }
  }

  @Test
  fun objectInWwwPublic() = runTest {
    val app = tester.sampleApps.recipes.publishedApp
    tester.publishApp(app)

    val owner = tester.newClient()
    val computer = owner.createComputer()
    val installedApp = computer.installApp(app)

    val recipesApp = installedApp.load() as RecipesApp
    recipesApp.platform.objectStore.put(
      PutObjectRequest(
        key = "www-public/resource1.txt",
        value = "This is an object in www-public".encodeUtf8(),
        contentType = "text/sample",
      ),
    )

    assertThat(owner.call().callApp(url = installedApp.url.resolve("/resource1.txt")!!))
      .isEqualTo(
        Response(
          headers = cacheHeaders("This is an object in www-public"),
          contentType = "text/sample".toMediaType(),
          body = ResponseBodySnapshot("This is an object in www-public"),
        ),
      )

    val anotherUser = tester.newClient()
    assertThat(anotherUser.call().callApp(url = installedApp.url.resolve("/resource1.txt")!!))
      .isEqualTo(
        Response(
          headers = cacheHeaders("This is an object in www-public"),
          contentType = "text/sample".toMediaType(),
          body = ResponseBodySnapshot("This is an object in www-public"),
        ),
      )
  }

  @Test
  fun objectInWwwPublicTakesPrecedenceOverObjectInWww() = runTest {
    val app = tester.sampleApps.recipes.publishedApp
    tester.publishApp(app)

    val client = tester.newClient()
    val computer = client.createComputer()
    val installedApp = computer.installApp(app)

    val recipesApp = installedApp.load() as RecipesApp
    recipesApp.platform.objectStore.put(
      PutObjectRequest(
        key = "www-public/resource1.txt",
        value = "This is an object in www-public".encodeUtf8(),
        contentType = "text/sample",
      ),
    )
    recipesApp.platform.objectStore.put(
      PutObjectRequest(
        key = "www/resource1.txt",
        value = "This is an object in www".encodeUtf8(),
        contentType = "text/standard",
      ),
    )

    assertThat(installedApp.call("/resource1.txt"))
      .isEqualTo(
        Response(
          headers = cacheHeaders("This is an object in www-public"),
          contentType = "text/sample".toMediaType(),
          body = ResponseBodySnapshot("This is an object in www-public"),
        ),
      )
  }

  @Test
  fun objectTakesPrecedenceOverResource() = runTest {
    val app = tester.sampleApps.recipes.publishedApp.copy(
      resources = tester.sampleApps.recipes.publishedApp.resources + mapOf(
        "www/resource1.txt" to "This is a resource in www".encodeUtf8(),
      ),
    )
    tester.publishApp(app)

    val client = tester.newClient()
    val computer = client.createComputer()
    val installedApp = computer.installApp(app)

    val recipesApp = installedApp.load() as RecipesApp
    recipesApp.platform.objectStore.put(
      PutObjectRequest(
        key = "www/resource1.txt",
        value = "This is an object in www".encodeUtf8(),
        contentType = "text/simple",
      ),
    )

    assertThat(installedApp.call("/resource1.txt"))
      .isEqualTo(
        Response(
          headers = cacheHeaders("This is an object in www"),
          contentType = "text/simple".toMediaType(),
          body = ResponseBodySnapshot("This is an object in www"),
        ),
      )
  }

  @Test
  fun indexHtmlIsServedToRequestsEndingInSlash() = runTest {
    val app = tester.sampleApps.recipes.publishedApp.copy(
      resources = tester.sampleApps.recipes.publishedApp.resources + mapOf(
        "www/index.html" to "This is the default resource".encodeUtf8(),
        "www/hello/index.html" to "This is the default resource for hello/".encodeUtf8(),
      ),
    )
    tester.publishApp(app)

    val client = tester.newClient()
    val computer = client.createComputer()
    val installedApp = computer.installApp(app)

    assertThat(installedApp.call("/"))
      .isEqualTo(
        Response(
          headers = cacheHeaders("This is the default resource"),
          contentType = "text/html".toMediaType(),
          body = ResponseBodySnapshot("This is the default resource"),
        ),
      )

    assertThat(installedApp.call("/hello/"))
      .isEqualTo(
        Response(
          headers = cacheHeaders("This is the default resource for hello/"),
          contentType = "text/html".toMediaType(),
          body = ResponseBodySnapshot("This is the default resource for hello/"),
        ),
      )
  }

  @Test
  fun indexHtmlIsNotServedToRequestsNotEndingInSlash() = runTest {
    val app = tester.sampleApps.recipes.publishedApp.copy(
      resources = tester.sampleApps.recipes.publishedApp.resources + mapOf(
        "www/hello/index.html" to "This is the default resource for hello/".encodeUtf8(),
      ),
    )
    tester.publishApp(app)

    val client = tester.newClient()
    val computer = client.createComputer()
    val installedApp = computer.installApp(app)

    assertFailsWith<NotFoundUserException> {
      installedApp.call("/hello")
    }
  }

  @Test
  fun conditionalGetReturnsNotModified() = runTest {
    val app = tester.sampleApps.recipes.publishedApp.copy(
      resources = tester.sampleApps.recipes.publishedApp.resources + mapOf(
        "www-public/resource1.txt" to "This is a cacheable resource".encodeUtf8(),
      ),
    )
    tester.publishApp(app)

    val client = tester.newClient()
    val computer = client.createComputer()
    val installedApp = computer.installApp(app)
    val url = installedApp.url.resolve("/resource1.txt")!!

    // A first request returns the body with an ETag.
    val first = client.call().callApp(url = url)
    val etag = first.header("ETag")!!
    assertThat(first.status).isEqualTo(200)

    // A revalidation with the same ETag returns 304 with no body.
    val second = client.call().callApp(
      url = url,
      headers = listOf(Header("If-None-Match", etag)),
    )
    assertThat(second).isEqualTo(
      Response(
        status = 304,
        headers = cacheHeaders("This is a cacheable resource"),
        contentType = "text/plain".toMediaType(),
        body = ResponseBodySnapshot(""),
      ),
    )
  }
}
