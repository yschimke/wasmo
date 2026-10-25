package com.wasmo.installedapps

import com.wasmo.identifiers.ForInstalledApp
import com.wasmo.identifiers.InstalledAppScope
import com.wasmo.identifiers.Deployment
import com.wasmo.identifiers.AppSlug
import com.wasmo.identifiers.ComputerSlug
import com.wasmo.http.PublicHttpsHttpService
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.time.Clock
import wasmo.app.Platform
import wasmo.downloader.Downloader
import wasmo.http.HttpService
import wasmo.jobs.JobQueue
import wasmo.objectstore.ObjectStore
import wasmo.sql.SqlService

@Inject
@SingleIn(InstalledAppScope::class)
class RealPlatform(
  override val clock: Clock,
  @ForInstalledApp override val httpService: HttpService,
  override val publicHttpsService: PublicHttpsHttpService,
  @ForInstalledApp override val objectStore: ObjectStore,
  @ForInstalledApp override val downloader: Downloader,
  @ForInstalledApp override val sqlService: SqlService,
  @ForInstalledApp override val jobQueueFactory: JobQueue.Factory,
  deployment: Deployment,
  appSlug: AppSlug,
  computerSlug: ComputerSlug,
) : Platform {
  override val applicationUrl: String = deployment.baseUrl.newBuilder()
    .host("$appSlug-$computerSlug.${deployment.baseUrl.host}")
    .encodedPath("/").query(null).fragment(null).build().toString()
}
