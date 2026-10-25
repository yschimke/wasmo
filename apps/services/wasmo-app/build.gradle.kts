plugins {
  alias(libs.plugins.kotlin.multiplatform)
  id("wasmo-build")
}

// Installed app archives supply browser assets. This module supplies shared owner services only.
wasmoBuild {
  libraryJvm()
}

kotlin {
  sourceSets {
    val jvmMain by getting {
      dependencies {
        implementation(libs.kotlinx.serialization.json)
        implementation(libs.okio)
        implementation(projects.platform.api)
      }
    }
    val jvmTest by getting {
      dependencies {
        implementation(libs.kotlinx.coroutines.test)
        implementation(projects.platform.testing)
      }
    }
  }
}
