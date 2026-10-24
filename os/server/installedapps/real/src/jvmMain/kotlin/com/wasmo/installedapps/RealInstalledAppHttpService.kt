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
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okio.ByteString
import wasmo.access.Caller
import wasmo.access.ComputerAccess
import wasmo.http.contentType
import wasmo.objectstore.GetObjectRequest
import wasmo.objectstore.ObjectStore
import wit.wasmo.http.v0_1_0.Header
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

    val publicObject = loadObjectOrNull(urlPath, "www-public", request)
    if (publicObject != null) return publicObject

    val publicResource = loadResourceOrNull(urlPath, "www-public", request)
    if (publicResource != null) return publicResource

    if (caller.computerAccess == ComputerAccess.Owner) {
      val privateObject = loadObjectOrNull(urlPath, "www", request)
      if (privateObject != null) return privateObject

      val privateResource = loadResourceOrNull(urlPath, "www", request)
      if (privateResource != null) return privateResource

      // TODO: pass the computerAccess to the HTTP service.
      val callHttpServiceResponse = callHttpService(request)
      if (callHttpServiceResponse != null) return callHttpServiceResponse
    }

    throw NotFoundUserException()
  }

  private suspend fun loadResourceOrNull(
    urlPath: String,
    prefix: String,
    request: Request,
  ): Response<ResponseBody>? {
    val resourceLoader = resourceLoaderFactory.create()
    val privateResource = resourceLoader.loadOrNull("/$prefix$urlPath")
      ?: return null

    return staticResponse(privateResource, contentTypeDatabase[urlPath], request)
  }

  private suspend fun loadObjectOrNull(
    urlPath: String,
    prefix: String,
    request: Request,
  ): Response<ResponseBody>? {
    val response = objectStore.get(
      GetObjectRequest(key = "$prefix$urlPath"),
    )
    val value = response.value ?: return null
    return staticResponse(
      value,
      response.contentType?.value?.toMediaTypeOrNull() ?: contentTypeDatabase[urlPath],
      request,
    )
  }

  /**
   * Serves [bytes] as a cacheable static response. The body is tagged with a strong [ETag] (the
   * content's SHA-256) so the browser can revalidate with `If-None-Match` instead of downloading it
   * again; a matching request gets an empty `304 Not Modified`. `Cache-Control: no-cache` means
   * "store it, but always revalidate", which is safe when a URL's content can change between app
   * versions (nothing here is content-hashed into the path).
   */
  private fun staticResponse(
    bytes: ByteString,
    contentType: MediaType?,
    request: Request,
  ): Response<ResponseBody> {
    val etag = "\"${bytes.sha256().hex()}\""
    val headers = listOf(
      Header("ETag", etag),
      Header("Cache-Control", "no-cache"),
    )

    val ifNoneMatch = request.headers
      .firstOrNull { it.name.equals("If-None-Match", ignoreCase = true) }
      ?.value
    if (ifNoneMatch == etag) {
      return Response(status = 304, headers = headers, contentType = contentType, body = ResponseBody {})
    }

    return Response(
      headers = headers,
      contentType = contentType,
      body = ResponseBody { it.write(bytes) },
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
