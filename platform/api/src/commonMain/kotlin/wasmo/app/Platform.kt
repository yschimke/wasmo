package wasmo.app

import kotlin.time.Clock
import wasmo.downloader.Downloader
import wasmo.http.HttpService
import wasmo.jobs.JobQueue
import wasmo.objectstore.ObjectStore
import wasmo.sql.SqlService

interface Platform {
  val clock: Clock
  val httpService: HttpService
  /** Bounded, redirect-free HTTPS to public Internet addresses. Null on older hosts. */
  val publicHttpsService: HttpService? get() = null
  /** Canonical installed app root URL from deployment configuration, never a forwarded Host. */
  val applicationUrl: String? get() = null
  /** Proxies the active release's manifest declares. Empty on older hosts. */
  suspend fun webProxies(): List<WebProxy> = listOf()
  val objectStore: ObjectStore
  val downloader: Downloader
  val sqlService: SqlService
  val jobQueueFactory: JobQueue.Factory
}
