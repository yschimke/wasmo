package wasmo.app

import wasmo.http.HttpService
import wasmo.jobs.JobHandler

abstract class WasmoApp {
  open val httpService: HttpService?
    get() = null
  open val jobHandlerFactory: JobHandler.Factory?
    get() = null

  /**
   * Invoked after an app is first installed, and after each version update.
   *
   * @param oldVersion will be 0 if this is the first install of the app.
   */
  open suspend fun afterInstall(
    oldVersion: Long,
    newVersion: Long,
  ) {
  }

  /**
   * Invoked when the owner removes the app, before its URLs stop serving. The app's data is kept
   * for a reinstall, so use this to give up grants held outside Wasmo. Failures are ignored.
   */
  open suspend fun beforeRemove() {
  }

  fun interface Factory {
    suspend fun create(platform: Platform): WasmoApp
  }
}
