package dev.jeary.koban.config

import dev.jeary.koban.storage.StorageSettings

/** Reads `storage:` from config.yml. */
object StorageConfig {

    fun parse(node: ConfigNode): StorageSettings {
        val type = node.choice("type", "sqlite", listOf("sqlite", "mysql", "mariadb")) { it.trim().lowercase().takeIf { t -> t in TYPES } }
        if (type == "sqlite") {
            val sqlite = node.nodeOrEmpty("sqlite")
            val file = sqlite.string("file", "koban.db").trim()
            if (file.isEmpty() || file.contains("..")) {
                sqlite.error("file", "expected a file name inside the plugin folder, got \"$file\"")
                return StorageSettings.Sqlite()
            }
            return StorageSettings.Sqlite(file)
        }
        val server = node.node("mysql", required = true) ?: node.nodeOrEmpty("mysql")
        val host = server.requireString("host")?.trim().orEmpty()
        val database = server.requireString("database")?.trim().orEmpty()
        val username = server.requireString("username")?.trim().orEmpty()
        if (database.isNotEmpty() && !DATABASE_NAME.matches(database)) {
            server.error("database", "expected letters, digits and underscores only, got \"$database\"")
        }
        return StorageSettings.Server(
            flavour = if (type == "mariadb") StorageSettings.Server.Flavour.MARIADB else StorageSettings.Server.Flavour.MYSQL,
            host = host.ifEmpty { "localhost" },
            port = server.int("port", 3306, 1..65535),
            database = database,
            username = username,
            password = server.string("password", ""),
            poolSize = server.int("pool-size", 6, 2..32),
            properties = server.stringMap("properties"),
        )
    }

    private val TYPES = setOf("sqlite", "mysql", "mariadb")
    private val DATABASE_NAME = Regex("^[A-Za-z0-9_]{1,64}$")
}
