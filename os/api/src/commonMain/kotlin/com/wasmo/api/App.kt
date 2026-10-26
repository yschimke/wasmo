package com.wasmo.api

import com.wasmo.identifiers.AppSlug
import kotlinx.serialization.Serializable

@Serializable
data class InstallAppRequest(
  /** This is either a URL or a file path. */
  val appManifestAddress: String,
  val appSlug: AppSlug,
)

@Serializable
data class InstallAppResponse(
  val url: String,
)

/** Removing an app disables it while retaining its data for a later reinstall. */
@Serializable
data class RemoveAppRequest(
  val appSlug: AppSlug,
)

@Serializable
data class RemoveAppResponse(
  val computer: ComputerSnapshot,
)

@Serializable
data class InstalledAppSnapshot(
  val slug: AppSlug,
  val launcherLabel: String,
  val maskableIconUrl: String,
  val homeUrl: String,
)

enum class InstallIncompleteReason {
  Unknown,
  SourceUnavailable,
  TargetCapacity,
}
