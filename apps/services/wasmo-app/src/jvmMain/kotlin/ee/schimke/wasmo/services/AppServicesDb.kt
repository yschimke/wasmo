package ee.schimke.wasmo.services

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import wasmo.sql.SqlConnection
import wasmo.sql.SqlDatabase

/** Per-install PostgreSQL storage, including the idempotent update simulation. */
internal class AppServicesDb(private val database: SqlDatabase) {
  suspend fun ensureSchema(): Unit = transaction { connection ->
    // Serializes first use even before the schema table exists. Locks belong to this app's
    // database.
    connection.execute("SELECT pg_advisory_xact_lock(76219251)")
    connection.execute(
      "CREATE TABLE IF NOT EXISTS wasmo_services_schema (singleton INTEGER PRIMARY KEY CHECK (singleton = 1), version INTEGER NOT NULL)"
    )
    connection.execute("INSERT INTO wasmo_services_schema VALUES (1, 0) ON CONFLICT DO NOTHING")
    val version =
      connection
        .executeQuery("SELECT version FROM wasmo_services_schema WHERE singleton = 1 FOR UPDATE")
        .use {
          checkNotNull(it.next()).getS32(0)!!
        }
    if (version > 1) throw NewerSchema()
    if (version == 0) {
      connection.execute(
        "CREATE TABLE wasmo_services_kv (key TEXT PRIMARY KEY, value TEXT NOT NULL)"
      )
      connection.execute("UPDATE wasmo_services_schema SET version = 1 WHERE singleton = 1")
    }
  }

  suspend fun read(): JsonObject =
    database.newConnection().use { connection ->
      val values = linkedMapOf<String, JsonElement>()
      connection.executeQuery("SELECT key, value FROM wasmo_services_kv ORDER BY key").use { rows ->
        while (true) {
          val row = rows.next() ?: break
          values[row.getString(0)!!] = Json.parseToJsonElement(row.getString(1)!!)
        }
      }
      JsonObject(values)
    }

  suspend fun put(key: String, value: JsonElement) = transaction { connection ->
    // Lock the quota check and write together across requests, app instances and OS restarts.
    connection.execute("LOCK TABLE wasmo_services_kv IN EXCLUSIVE MODE")
    val serialized = value.toString()
    val otherBytes =
      connection
        .executeQuery(
          "SELECT COALESCE(SUM(octet_length(key) + octet_length(value)), 0)::BIGINT FROM wasmo_services_kv WHERE key <> $1"
        ) {
          bindString(0, key)
        }
        .use { it.next()!!.getS64(0)!! }
    if (otherBytes + key.toByteArray().size + serialized.toByteArray().size > TOTAL_BYTES)
      throw StorageLimit()
    connection.execute(
      "INSERT INTO wasmo_services_kv (key, value) VALUES ($1, $2) ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value"
    ) {
      bindString(0, key)
      bindString(1, serialized)
    }
  }

  /** Bootstrap once, atomically: concurrent devices cannot overwrite an existing install. */
  suspend fun importIfEmpty(values: JsonObject) = transaction { connection ->
    connection.execute("LOCK TABLE wasmo_services_kv IN EXCLUSIVE MODE")
    val exists =
      connection.executeQuery("SELECT key FROM wasmo_services_kv LIMIT 1").use { it.next() != null }
    if (exists) throw AlreadyInitialized()
    val bytes =
      values.entries.sumOf { (key, value) ->
        key.toByteArray().size + value.toString().toByteArray().size
      }
    if (bytes > TOTAL_BYTES) throw StorageLimit()
    for ((key, value) in values) {
      connection.execute("INSERT INTO wasmo_services_kv (key, value) VALUES ($1, $2)") {
        bindString(0, key)
        bindString(1, value.toString())
      }
    }
  }

  suspend fun delete(key: String) =
    database.newConnection().use { connection ->
      connection.execute("DELETE FROM wasmo_services_kv WHERE key = $1") { bindString(0, key) }
    }

  private suspend fun <T> transaction(block: suspend (SqlConnection) -> T): T =
    database.newConnection().use { connection ->
      connection.execute("BEGIN")
      try {
        val result = block(connection)
        connection.execute("COMMIT")
        result
      } catch (failure: Throwable) {
        try {
          connection.execute("ROLLBACK")
        } catch (rollback: Throwable) {
          failure.addSuppressed(rollback)
        }
        throw failure
      }
    }

  class AlreadyInitialized : Exception()

  class NewerSchema : Exception()

  class StorageLimit : Exception()

  companion object {
    const val VALUE_BYTES = 256 * 1024
    const val TOTAL_BYTES = 1024 * 1024
  }
}
