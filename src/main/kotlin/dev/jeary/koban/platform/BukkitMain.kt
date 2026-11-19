package dev.jeary.koban.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.bukkit.Bukkit
import org.bukkit.plugin.IllegalPluginAccessException
import org.bukkit.plugin.Plugin
import java.util.concurrent.CancellationException
import java.util.logging.Level
import kotlin.coroutines.CoroutineContext

/**
 * A coroutine dispatcher for the server's main thread. Anything that touches the Bukkit API runs
 * in `withContext(main) { … }`; JDA callbacks and database work never do.
 *
 * Dispatching is skipped when already on the main thread, so `withContext(main)` from a command
 * handler costs nothing.
 *
 * Once the plugin is disabling, the scheduler refuses new tasks. The coroutine is then cancelled
 * and resumed on an IO thread, where it only sees the cancellation: Bukkit code is never run off
 * the main thread to "finish up".
 */
class MainThreadDispatcher(private val plugin: Plugin) : CoroutineDispatcher() {

    override fun isDispatchNeeded(context: CoroutineContext): Boolean = !Bukkit.isPrimaryThread()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        if (plugin.isEnabled) {
            try {
                Bukkit.getScheduler().runTask(plugin, block)
                return
            } catch (_: IllegalPluginAccessException) {
                // Disabled between the check and the call: fall through.
            }
        }
        context[Job]?.cancel(CancellationException("Koban is disabling; main-thread work was dropped"))
        Dispatchers.IO.dispatch(context, block)
    }

    override fun toString(): String = "Bukkit.Main"
}

/** The plugin's root coroutine scope. One failing task never cancels the others. */
class PluginScope(plugin: Plugin) : CoroutineScope {
    private val handler = CoroutineExceptionHandler { _, error ->
        plugin.logger.log(Level.SEVERE, "Unhandled error in a background task", error)
    }
    override val coroutineContext: CoroutineContext = SupervisorJob() + Dispatchers.Default + handler

    fun shutdown() = cancel("Koban is disabling")
}
