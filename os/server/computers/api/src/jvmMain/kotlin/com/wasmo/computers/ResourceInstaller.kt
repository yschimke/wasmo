package com.wasmo.computers

import com.wasmo.identifiers.AppSlug
import com.wasmo.identifiers.WasmoFileAddress
import com.wasmo.packaging.AppManifest
import com.wasmo.support.issues.IssueCollector

interface ResourceInstaller {
  context(issueCollector: IssueCollector)
  suspend fun install(): AppManifest?

  /** Deletes resources from versions other than [keepVersion], once that version is active. */
  suspend fun pruneOtherVersions(keepVersion: Long) {
  }

  interface Factory {
    fun create(
      appSlug: AppSlug,
      wasmoFileAddress: WasmoFileAddress,
    ): ResourceInstaller
  }
}
