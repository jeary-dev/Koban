package dev.jeary.koban

import dev.jeary.koban.config.ConfigLoader
import dev.jeary.koban.config.ValidationResult
import dev.jeary.koban.domain.version.ServerVersion
import dev.jeary.koban.platform.compat.Platform
import dev.jeary.koban.storage.DatabaseFactory
import dev.jeary.koban.storage.StorageSettings
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import java.util.logging.Level

/** Koban's entry point: validates config, opens storage, and hands over to [KobanCore]. */
class KobanPlugin : JavaPlugin() {

    private var core: KobanCore? = null

    override fun onEnable() {
        val serverVersion = ServerVersion.parse(Bukkit.getBukkitVersion())
        val platform = Platform.detect()
        banner(serverVersion, platform)

        val config = when (val loaded = ConfigLoader(this).load()) {
            is ValidationResult.Invalid -> {
                loaded.errors.forEach { logger.severe(it.toString()) }
                logger.severe("Koban is disabled until the problems above are fixed.")
                server.pluginManager.disablePlugin(this)
                return
            }
            is ValidationResult.Valid -> {
                loaded.warnings.forEach { logger.warning(it.toString()) }
                loaded.value
            }
        }

        val storage = config.core.storage
        val database = try {
            DatabaseFactory.create(storage, dataFolder, logger)
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "Could not open ${describe(storage)}: ${e.message}. Koban is disabled.", e)
            server.pluginManager.disablePlugin(this)
            return
        }
        logger.info("Storage ready: ${describe(storage)}.")

        val started = KobanCore(this, config, database, platform, describe(storage))
        core = started
        try {
            started.enable()
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "Koban could not start: ${e.message}", e)
            server.pluginManager.disablePlugin(this)
            return
        }
        logger.info("Text: ${started.sink.name}. ${config.reasons.categories.size} report reasons loaded.")
    }

    override fun onDisable() {
        core?.let {
            it.disable()
            it.database.close()
        }
        core = null
    }

    private fun banner(version: ServerVersion?, platform: Platform) {
        val running = "${Bukkit.getName()} ${version ?: Bukkit.getBukkitVersion()} (${platform.displayName} API) on Java ${Runtime.version().feature()}"
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
