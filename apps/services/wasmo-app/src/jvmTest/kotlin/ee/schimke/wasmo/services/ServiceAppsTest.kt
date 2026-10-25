package ee.schimke.wasmo.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ServiceAppsTest {
  @Test
  fun disabledByDefault() {
    assertEquals(emptySet(), serviceApps(null))
    assertEquals(emptySet(), serviceApps(" , "))
  }

  @Test
  fun supportsSeveralAppsWithoutDuplicates() {
    assertEquals(
      setOf("terrazzo", "notes", "photos", "tasks"),
      serviceApps("terrazzo, notes,photos,tasks,notes"),
    )
  }

  @Test
  fun rejectsWildcardAndInvalidInstallNames() {
    for (value in listOf("*", "yschimke/app", "App", "../app", "a".repeat(16))) {
      assertFailsWith<IllegalArgumentException> { serviceApps(value) }
    }
  }
}
