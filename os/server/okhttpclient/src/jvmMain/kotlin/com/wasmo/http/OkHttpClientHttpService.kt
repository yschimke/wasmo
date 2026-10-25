package com.wasmo.http

import com.wasmo.identifiers.OsScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.IOException
import wasmo.http.HttpService
import wasmo.http.httpUrl
import wit.wasmo.http.v0_1_0.Header
import wit.wasmo.http.v0_1_0.HttpRequest
import wit.wasmo.http.v0_1_0.HttpResponse

@SingleIn(OsScope::class)
class OkHttpClientHttpService
internal constructor(
  private val callFactory: Call.Factory,
  private val maxResponseBytes: Long?,
) : HttpService {
  @Inject constructor(callFactory: Call.Factory) : this(callFactory, null)

  override suspend fun execute(request: HttpRequest): HttpResponse {
    val call = callFactory.newCall(request.toOkHttp())
    return suspendCancellableCoroutine { continuation ->
      continuation.invokeOnCancellation {
        call.cancel()
      }
      call.enqueue(
        object : Callback {
          override fun onFailure(call: Call, e: IOException) {
            continuation.resumeWithException(e)
          }

          override fun onResponse(call: Call, response: Response) {
            response.use {
              try {
                continuation.resume(response.toWasmo(maxResponseBytes))
              } catch (failure: IOException) {
                continuation.resumeWithException(failure)
              }
            }
          }
        }
      )
    }
  }
}

internal fun HttpRequest.toOkHttp(): Request {
  var contentType: MediaType? = null
  val headersBuilder = Headers.Builder()

  for (header in headers) {
    when {
      header.name.equals("Content-Type", ignoreCase = true) -> {
        contentType = header.value.toMediaType()
      }

      else -> {
        headersBuilder.add(header.name, header.value)
      }
    }
  }

  return Request(
    url = httpUrl,
    headers = headersBuilder.build(),
    method = method,
    body = body?.toRequestBody(contentType),
  )
}

internal fun Response.toWasmo(maxBytes: Long? = null): HttpResponse {
  val headers = headers.map { Header(it.first, it.second) }.toMutableList()

  val contentType = body.contentType()
  if (contentType != null) {
    headers += Header("Content-Type", contentType.toString())
  }

  val bytes =
    if (maxBytes == null) body.byteString()
    else {
      val buffer = Buffer()
      val source = body.source()
      while (buffer.size <= maxBytes) {
        if (source.read(buffer, minOf(8192L, maxBytes + 1 - buffer.size)) == -1L) break
      }
      buffer.readByteString()
    }
  if (maxBytes != null && bytes.size > maxBytes) throw IOException("Response is too large")
  return HttpResponse(
    code = code.toUInt(),
    headers = headers,
    body = bytes,
  )
}
