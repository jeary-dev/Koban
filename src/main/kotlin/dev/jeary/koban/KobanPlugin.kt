package dev.jeary.koban

import dev.jeary.koban.config.ConfigError
import dev.jeary.koban.config.ConfigNode
import dev.jeary.koban.config.StorageConfig
import dev.jeary.koban.domain.version.ServerVersion
import dev.jeary.koban.storage.Database
import dev.jeary.koban.storage.DatabaseFactory
import dev.jeary.koban.storage.StorageSettings
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import java.util.logging.Level

/** Koban's entry point: loads config, opens storage, and wires everything together. */
class KobanPlugin : JavaPlugin() {

    private var database: Database? = null

    override fun onEnable() {
        saveDefaultConfig()
        val serverVersion = ServerVersion.parse(Bukkit.getBukkitVersion())
        banner(serverVersion)

        val problems = ArrayList<ConfigError>()
        val root = ConfigNode("config.yml", config, "", problems)
        val storage = StorageConfig.parse(root.nodeOrEmpty("storage"))
        if (problems.any { it.isError }) {
            problems.forEach { logger.severe(it.toString()) }
            logger.severe("Koban is disabled until config.yml is fixed.")
            server.pluginManager.disablePlugin(this)
            return
        }

        database = try {
            DatabaseFactory.create(storage, dataFolder, logger)
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "Could not open ${describe(storage)}: ${e.message}. Koban is disabled.", e)
            server.pluginManager.disablePlugin(this)
            return
        }
        logger.info("Storage ready: ${describe(storage)}.")
    }

    override fun onDisable() {
        database?.close()
        database = null
    }

    private fun banner(version: ServerVersion?) {
        val running = "${Bukkit.getName()} ${version ?: Bukkit.getBukkitVersion()} on Java ${Runtime.version().feature()}"
        logger.info("Koban ${description.version} - $running")
        if (version != null && version < ServerVersion.MINIMUM) {
            logger.warning("Koban supports ${ServerVersion.MINIMUM} and newer; this server is $version. Expect problems.")
        }
    }

    private fun describe(storage: StorageSettings): String = when (storage) {
        is StorageSettings.Sqlite -> "SQLite (${storage.file})"
        is StorageSettings.Server -> "${storage.flavour.name.lowercase()} at ${storage.host}:${storage.port}/${storage.database}"
    }
}
