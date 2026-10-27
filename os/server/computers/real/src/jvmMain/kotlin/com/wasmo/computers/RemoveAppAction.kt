package com.wasmo.computers

import com.wasmo.accounts.CallScope
import com.wasmo.accounts.Client
import com.wasmo.api.RemoveAppRequest
import com.wasmo.api.RemoveAppResponse
import com.wasmo.api.WasmoJson
import com.wasmo.db.installedapps.deactivateInstalledApp
import com.wasmo.framework.ArgumentUserException
import com.wasmo.framework.HttpAction
import com.wasmo.framework.NotFoundUserException
import com.wasmo.framework.Request
import com.wasmo.framework.Response
import com.wasmo.framework.ResponseBody
import com.wasmo.framework.Url
import com.wasmo.framework.requireSameOrigin
import com.wasmo.identifiers.ComputerSlug
import com.wasmo.installedapps.InstalledAppStore
import dev.zacsweers.metro.ClassKey
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.SerializationException
import okhttp3.MediaType.Companion.toMediaType
import wasmo.sql.SqlDatabase
import wasmox.sql.transaction

@Inject
@ClassKey
@ContributesIntoMap(CallScope::class)
class RemoveAppAction(
  private val client: Client,
  private val computerStore: ComputerStore,
  private val wasmoDb: SqlDatabase,
  private val installedAppStore: InstalledAppStore,
) : HttpAction {
  suspend fun remove(
    computerSlug: ComputerSlug,
    request: RemoveAppRequest,
  ): Response<RemoveAppResponse> {
    val installedApp = wasmoDb.transaction {
      installedAppStore.getOrNull(client, computerSlug, request.appSlug)
    }
    // Let the app give up outside grants (such as a Home Assistant sign-in) before its URL goes
    // away. A failing hook never blocks removal: the owner asked for the app to be gone.
    try {
      installedApp?.app()?.beforeRemove()
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
    }
    val computer = wasmoDb.transaction {
      val computer =
        computerStore.getOrNull(client, computerSlug)
          ?: throw NotFoundUserException("unexpected computer: ${computerSlug.value}")
      deactivateInstalledApp(computer.id, request.appSlug)
      computer
    }
    return Response(body = RemoveAppResponse(computer.snapshot()))
  }

  override suspend fun invoke(url: Url, request: Request): Response<ResponseBody> {
    // A same-origin JSON POST prevents links, forms, or another app origin removing an app.
    val origin = request.headers.singleOrNull { it.name.equals("Origin", ignoreCase = true) }?.value
    requireSameOrigin(url, origin)
    val contentType =
      request.headers
        .singleOrNull {
          it.name.equals("Content-Type", ignoreCase = true)
        }
        ?.value
        ?.substringBefore(';')
        ?.trim()
    if (
      request.method != "POST" ||
        !contentType.equals("application/json", ignoreCase = true)
    ) {
      throw ArgumentUserException("expected a same-origin JSON POST")
    }
    val body =
      try {
        WasmoJson.decodeFromString<RemoveAppRequest>(
          request.body?.utf8() ?: throw ArgumentUserException("missing request body")
        )
      } catch (e: SerializationException) {
        throw ArgumentUserException("invalid remove app request")
      }
    val response =
      remove(
        ComputerSlug(value = url.subdomain ?: throw NotFoundUserException()),
        body,
      )
    return Response(
      contentType = "application/json".toMediaType(),
      body = ResponseBody { sink -> sink.writeUtf8(WasmoJson.encodeToString(response.body)) },
    )
  }
}
