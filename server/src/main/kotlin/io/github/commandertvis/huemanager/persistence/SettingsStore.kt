package io.github.commandertvis.huemanager.persistence

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * A tiny key/value store backed by a SQLite file. Used to persist runtime-mutable settings
 * (on/off user state, schedule preferences) so they survive server restarts. Secrets and
 * bootstrap configuration stay in `.env`.
 *
 * Access is serialized through a single connection guarded by [lock]; settings writes are
 * infrequent, so this is simpler and safer than juggling concurrent connections.
 */
class SettingsStore(dbPath: Path) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(SettingsStore::class.java)
    private val lock = Any()
    private val connection: Connection

    init {
        dbPath.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        connection = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        connection.createStatement().use { statement ->
            statement.executeUpdate("PRAGMA journal_mode=WAL")
            statement.executeUpdate(
                "CREATE TABLE IF NOT EXISTS app_state (key TEXT PRIMARY KEY, value TEXT NOT NULL)"
            )
        }
        logger.info("Settings store ready at $dbPath")
    }

    fun get(key: String): String? = synchronized(lock) {
        connection.prepareStatement("SELECT value FROM app_state WHERE key = ?").use { ps ->
            ps.setString(1, key)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    fun put(key: String, value: String): Unit = synchronized(lock) {
        connection.prepareStatement(
            "INSERT INTO app_state(key, value) VALUES(?, ?) " +
                "ON CONFLICT(key) DO UPDATE SET value = excluded.value"
        ).use { ps ->
            ps.setString(1, key)
            ps.setString(2, value)
            ps.executeUpdate()
        }
    }

    override fun close(): Unit = synchronized(lock) { connection.close() }
}
