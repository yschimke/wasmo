package com.wasmo.installedapps

import com.wasmo.framework.ContentTypeDatabase
import com.wasmo.framework.NotFoundUserException
import com.wasmo.framework.Request
import com.wasmo.framework.Response
import com.wasmo.framework.ResponseBody
import com.wasmo.identifiers.ForInstalledApp
import com.wasmo.identifiers.InstalledAppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import wasmo.access.Caller
import wasmo.access.ComputerAccess
import wasmo.http.contentType
import wasmo.objectstore.GetObjectRequest
import wasmo.objectstore.ObjectStore
import wit.wasmo.http.v0_1_0.Header as PlatformHeader
import wit.wasmo.http.v0_1_0.HttpRequest
import wit.wasmo.http.v0_1_0.HttpResponse
import wit.wasmo.http.v0_1_0.Url

/**
 * Attempts to satisfy an HTTP request according to our routing precedence rules.
 */
@Inject
@SingleIn(InstalledAppScope::class)
class RealInstalledAppHttpService(
  private val installedAppService: InstalledAppService,
  private val resourceLoaderFactory: ResourceLoader.Factory,
  private val contentTypeDatabase: ContentTypeDatabase,
  @ForInstalledApp private val objectStore: ObjectStore,
) : InstalledAppHttpService {
  override suspend fun execute(
    caller: Caller,
    request: Request,
  ): Response<ResponseBody> {
    val encodedUrlPath = request.url.encodedPath
    val urlPath = when {
      encodedUrlPath.endsWith("/") -> "${encodedUrlPath}index.html"
      else -> encodedUrlPath
    }

    val publicObject = loadObjectOrNull(urlPath, "www-public")
    if (publicObject != null) return publicObject

    val publicResource = loadResourceOrNull(request, urlPath, "www-public")
    if (publicResource != null) return publicResource

    if (caller.computerAccess == ComputerAccess.Owner) {
      val privateObject = loadObjectOrNull(urlPath, "www")
      if (privateObject != null) return privateObject

      val privateResource = loadResourceOrNull(request, urlPath, "www")
      if (privateResource != null) return privateResource

      // TODO: pass the computerAccess to the HTTP service.
      val callHttpServiceResponse = callHttpService(request)
      if (callHttpServiceResponse != null) return callHttpServiceResponse
    }

    throw NotFoundUserException()
  }

  private suspend fun loadResourceOrNull(
    request: Request,
    urlPath: String,
    prefix: String,
  ): Response<ResponseBody>? {
    val resourceLoader = resourceLoaderFactory.create()
    val privateResource = resourceLoader.loadOrNull("/$prefix$urlPath")
      ?: return null

    // Resources only change when the app is upgraded, but their names don't carry the version, so
    // let browsers keep them and revalidate each use: an unchanged multi-MB .wasm is then a 304.
    val etag = "\"${privateResource.sha256().hex().take(32)}\""
    val headers = listOf(
      PlatformHeader("Cache-Control", if (prefix == "www") "private, no-cache" else "no-cache"),
      PlatformHeader("ETag", etag),
    )
    val ifNoneMatch = request.headers
      .filter { it.name.equals("If-None-Match", ignoreCase = true) }
      .flatMap { it.value.split(',') }
      .map { it.trim().removePrefix("W/") }
    if (etag in ifNoneMatch) {
      return Response(status = 304, headers = headers, body = ResponseBody {})
    }

    return Response(
      headers = headers,
      contentType = contentTypeDatabase[urlPath],
      body = ResponseBody {
        it.write(privateResource)
      },
    )
  }

  private suspend fun loadObjectOrNull(
    urlPath: String,
    prefix: String,
  ): Response<ResponseBody>? {
    val response = objectStore.get(
      GetObjectRequest(key = "$prefix$urlPath"),
    )
    val value = response.value ?: return null
    return Response(
      contentType = response.contentType?.value?.toMediaTypeOrNull()
        ?: contentTypeDatabase[urlPath],
      body = ResponseBody {
        it.write(value)
      },
    )
  }

  private suspend fun callHttpService(request: Request): Response<ResponseBody>? {
    val httpService = installedAppService.app()?.httpService ?: return null
    val httpResponse = httpService.execute(
      request = request.toApplicationHttpRequest(),
    )
    return httpResponse.toOsHttpResponse()
  }

  private fun Request.toApplicationHttpRequest() = HttpRequest(
    method = method,
    url = Url(url.toString()),
    headers = headers.map { PlatformHeader(it.name, it.value) },
    body = body,
  )

  private fun HttpResponse.toOsHttpResponse(): Response<ResponseBody> = Response(
    status = this.code.toInt(),
    headers = this.headers,
    contentType = this.contentType?.toMediaTypeOrNull(),
    body = ResponseBody { sink -> sink.write(body) },
  )
}
