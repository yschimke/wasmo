package com.wasmo.http

import com.wasmo.identifiers.OsScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.OkHttpClient
import wasmo.http.HttpService
import wasmo.http.httpUrl
import wit.wasmo.http.v0_1_0.HttpRequest
import wit.wasmo.http.v0_1_0.HttpResponse

/** A separate client: existing platform requests and installer behavior remain unchanged. */
@Inject
@SingleIn(OsScope::class)
class PublicHttpsHttpService : HttpService {
  private val delegate = OkHttpClientHttpService(publicHttpsClient(), MAX_RESPONSE_BYTES)

  override suspend fun execute(request: HttpRequest): HttpResponse {
    val url = request.httpUrl
    require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.fragment == null)
    require((request.body?.size ?: 0) <= MAX_REQUEST_BYTES)
    // IP literals bypass OkHttp's DNS hook, so check them before creating the call too.
    if (url.host.contains(':') || url.host.all { it.isDigit() || it == '.' }) {
      require(isPublicAddress(InetAddress.getByName(url.host)))
    }
    return delegate.execute(request)
  }

  companion object {
    private const val MAX_REQUEST_BYTES = 64 * 1024L
    // Pocket Casts lists (Up Next with its episode sync, in-progress episodes) outgrow 64 KiB.
    private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024L
  }
}

internal fun publicHttpsClient(dns: Dns = Dns.SYSTEM): OkHttpClient =
  OkHttpClient.Builder()
    .dns(PublicInternetDns(dns))
    // A proxy would resolve destinations elsewhere and bypass the address policy.
    .proxy(Proxy.NO_PROXY)
    .followRedirects(false)
    .followSslRedirects(false)
    .retryOnConnectionFailure(false)
    .callTimeout(10, TimeUnit.SECONDS)
    .connectTimeout(3, TimeUnit.SECONDS)
    .readTimeout(5, TimeUnit.SECONDS)
    .build()

internal class PublicInternetDns(private val delegate: Dns) : Dns {
  override fun lookup(hostname: String): List<InetAddress> {
    val addresses = delegate.lookup(hostname)
    // Reject a mixed response rather than allowing private failover or a second lookup.
    if (addresses.isEmpty() || addresses.any { !isPublicAddress(it) }) {
      throw UnknownHostException("Destination is not on the public Internet")
    }
    return addresses
  }
}

internal fun isPublicAddress(address: InetAddress): Boolean {
  val bytes = address.address.map { it.toInt() and 255 }
  if (bytes.size == 4) {
    val (a, b, c, d) = bytes
    return !(a == 0 ||
      a == 10 ||
      a == 127 ||
      a >= 224 ||
      (a == 100 && b in 64..127) ||
      (a == 169 && b == 254) ||
      (a == 172 && b in 16..31) ||
      (a == 192 && (b == 168 || (b == 0 && c in listOf(0, 2)) || (b == 88 && c == 99))) ||
      (a == 198 && (b in 18..19 || (b == 51 && c == 100))) ||
      (a == 203 && b == 0 && c == 113) ||
      (a == 168 && b == 63 && c == 129 && d == 16))
  }
  // Conservatively allow global unicast only; exclude special-purpose, documentation and
  // transition ranges. IPv4-mapped addresses are normalized by InetAddress and checked above.
  if (bytes.size != 16 || bytes[0] !in 0x20..0x3f) return false
  if (bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] <= 1) return false // 2001::/23
  if (bytes.take(4) == listOf(0x20, 0x01, 0x0d, 0xb8)) return false
  if (bytes.take(2) == listOf(0x20, 0x02)) return false // 6to4
  if (bytes[0] == 0x3f && bytes[1] == 0xff && bytes[2] < 0x10) return false // 3fff::/20
  return true
}
