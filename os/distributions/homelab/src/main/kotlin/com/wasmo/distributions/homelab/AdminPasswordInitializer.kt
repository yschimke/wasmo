package com.wasmo.distributions.homelab

import com.wasmo.common.logging.Logger
import com.wasmo.db.usernames.findAccountId
import com.wasmo.identifiers.AccountPassword
import com.wasmo.identifiers.OsScope
import com.wasmo.identifiers.UsernameSlug
import com.wasmo.passwords.PasswordStore
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import wasmo.sql.SqlDatabase
import wasmox.sql.transaction

/**
 * Sets a password on the bootstrapped `admin` account at startup when `WASMO_ADMIN_PASSWORD` is
 * provided and the account has none yet. Without this, a homelab exposed on a public domain lets
 * anyone sign in as `admin`, since the account is created passwordless and the only other way to
 * set a password is the create-username flow.
 *
 * Idempotent: [PasswordStore.setPassword] with an empty old password succeeds only while the
 * account is still passwordless, so a later boot with the same env is a no-op and an existing
 * password is never overwritten.
 */
@Inject
@SingleIn(OsScope::class)
class AdminPasswordInitializer(
  private val wasmoDb: SqlDatabase,
  private val passwordStore: PasswordStore,
  private val logger: Logger,
) {
  suspend fun ensureAdminPassword(password: AccountPassword) {
    wasmoDb.transaction {
      val adminId = findAccountId(UsernameSlug("admin"))
      if (adminId == null) {
        logger.info("WASMO_ADMIN_PASSWORD set but no admin account found; skipping")
        return@transaction
      }

      val changed = passwordStore.setPassword(adminId, AccountPassword.EMPTY, password)
      when {
        changed -> logger.info("set a password on the admin account from WASMO_ADMIN_PASSWORD")
        else -> logger.info("admin account already has a password; leaving it unchanged")
      }
    }
  }
}
