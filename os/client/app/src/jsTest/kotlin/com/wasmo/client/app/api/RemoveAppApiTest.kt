package com.wasmo.client.app.api

import com.wasmo.api.ComputerSnapshot
import com.wasmo.api.RemoveAppRequest
import com.wasmo.api.RemoveAppResponse
import com.wasmo.api.WasmoJson
import com.wasmo.identifiers.AppSlug
import com.wasmo.identifiers.ComputerSlug
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.browser.window
import kotlinx.coroutines.test.runTest

class RemoveAppApiTest {
  @Test
  fun sendsJsonPostAndReadsTheUpdatedComputer() = runTest {
    val originalFetch = window.asDynamic().fetch
    val response = RemoveAppResponse(ComputerSnapshot(ComputerSlug("yuri"), emptyList()))
    val json = WasmoJson.encodeToString(response)
    var called = false
    val fakeFetch: (String, dynamic) -> Promise<dynamic> = { path, request ->
      called = true
      assertEquals("/remove-app", path)
      assertEquals("POST", request.method as String)
      assertEquals("application/json", request.headers["Content-Type"] as String)
      assertEquals(
        RemoveAppRequest(AppSlug("terrazzo2")),
        WasmoJson.decodeFromString<RemoveAppRequest>(request.body as String),
      )
      val result = js("({status: 200})")
      val readJson: () -> Promise<dynamic> = { Promise.resolve<dynamic>(JSON.parse<dynamic>(json)) }
      result.json = readJson
      Promise.resolve<dynamic>(result)
    }
    window.asDynamic().fetch = fakeFetch
    try {
      assertEquals(response, RealWasmoApi().removeApp(RemoveAppRequest(AppSlug("terrazzo2"))))
      assertEquals(true, called)
    } finally {
      window.asDynamic().fetch = originalFetch
    }
  }

  @Test
  fun failedRemovalPropagatesAnError() = runTest {
    val originalFetch = window.asDynamic().fetch
    val failedFetch: (dynamic, dynamic) -> Promise<dynamic> = { _, _ ->
      Promise.resolve<dynamic>(js("({status: 500})"))
    }
    window.asDynamic().fetch = failedFetch
    try {
      assertFailsWith<Exception> {
        RealWasmoApi().removeApp(RemoveAppRequest(AppSlug("terrazzo2")))
      }
    } finally {
      window.asDynamic().fetch = originalFetch
    }
  }
}
