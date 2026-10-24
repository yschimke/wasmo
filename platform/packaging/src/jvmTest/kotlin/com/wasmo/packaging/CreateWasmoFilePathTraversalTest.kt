package com.wasmo.packaging

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import com.wasmo.support.issues.IssueCollector
import kotlin.test.Test
import kotlinx.serialization.encodeToString
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import okio.openZip

/**
 * Minimal repro: an app manifest's `external_resource.from` is not confined to the project, so a
 * manifest can pull files from anywhere on the host the packaging process can read into the
 * `.wasmo` archive.
 *
 * `AppManifestChecker` validates `to` (rejects `..` segments and requires a leading `/`) but never
 * looks at `from`, and `CreateWasmoFile` resolves `from` with `baseDir.resolve(from, normalize =
 * true)`. An absolute `from` therefore escapes the input directory entirely — as does a `from` with
 * enough `..` segments. (`..` in `from` is itself intended: `happyPath` in CreateWasmoFileTest uses
 * `../../external-sources` to reach a sibling build output, which is why the fix is to confine the
 * resolved path to an allowed root, not to ban `..`.)
 *
 * The same unchecked `from` is concatenated at serve time in
 * `FileSystemResourceLoader.loadResolved`, so a file-system install (a developer-distribution
 * feature, where `allowExternalResources = true`) can read host files on request as well as at
 * packaging time.
 */
class CreateWasmoFilePathTraversalTest {
  private val fileSystem = FakeFileSystem()

  @Test
  fun packagesFilesFromOutsideTheProjectTree() {
    // A file outside the app directory, standing in for any host file the packaging process can
    // read (an object-store root, a private key, /etc/passwd, ...).
    fileSystem.createDirectories("/host-secrets".toPath())
    fileSystem.write("/host-secrets/credentials".toPath()) {
      writeUtf8("TOP SECRET host file the app should not be able to reach")
    }

    val manifest = AppManifest(
      target = "https://wasmo.com/sdk/1",
      version = 1,
      external_resource = listOf(
        // Absolute `from`: escapes the input directory outright.
        ExternalResource(from = "/host-secrets", to = "/www/leaked"),
      ),
    )

    fileSystem.createDirectories("/sources/evil.wasmo".toPath())
    fileSystem.write("/sources/evil.wasmo/wasmo-manifest.toml".toPath()) {
      writeUtf8(WasmoToml.encodeToString<AppManifest>(manifest))
    }

    val issues = IssueCollector.collect {
      CreateWasmoFile(
        fileSystem = fileSystem,
        inputDirectory = "/sources/evil.wasmo".toPath(),
        outputFile = "/outputs/evil.wasmo".toPath(),
      ).execute()
    }

    // The manifest is accepted with no error: `from` is never validated.
    assertThat(issues).isEmpty()

    // The host file outside the project is now served by the packaged app.
    val output = fileSystem.openZip("/outputs/evil.wasmo".toPath())
    assertThat(output.read("/www/leaked/credentials".toPath()) { readUtf8() })
      .isEqualTo("TOP SECRET host file the app should not be able to reach")
  }
}
