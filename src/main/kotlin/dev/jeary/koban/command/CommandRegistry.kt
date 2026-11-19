package dev.jeary.koban.command

import dev.jeary.koban.KobanCore
import dev.jeary.koban.platform.Messenger.Companion.tag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.command.CommandSender
import org.incendo.cloud.Command
import org.incendo.cloud.bukkit.CloudBukkitCapabilities
import org.incendo.cloud.exception.ArgumentParseException
import org.incendo.cloud.exception.CommandExecutionException
import org.incendo.cloud.exception.InvalidCommandSenderException
import org.incendo.cloud.exception.InvalidSyntaxException
import org.incendo.cloud.exception.NoPermissionException
import org.incendo.cloud.execution.ExecutionCoordinator
import org.incendo.cloud.paper.LegacyPaperCommandManager
import java.util.logging.Level

/**
 * Owns the Incendo Cloud command manager and turns every Cloud error into a messages.yml message.
 *
 * On Paper the commands are also registered with Brigadier, so the client shows argument hints;
 * everything Koban registers uses plain word and greedy-string arguments, which Brigadier accepts
 * on every supported version. On Spigot, tab completion goes through Bukkit as usual.
 */
class CommandRegistry(private val core: KobanCore) {

    val manager: LegacyPaperCommandManager<CommandSender> =
        LegacyPaperCommandManager.createNative(core.plugin, ExecutionCoordinator.simpleCoordinator())

    private val messenger get() = core.messenger

    fun enable() {
        when {
            manager.hasCapability(CloudBukkitCapabilities.NATIVE_BRIGADIER) -> try {
                manager.registerBrigadier()
            } catch (e: RuntimeException) {
                core.logger.info("Brigadier command hints aren't available (${e.javaClass.simpleName}); commands still work.")
            }
            manager.hasCapability(CloudBukkitCapabilities.ASYNCHRONOUS_COMPLETION) -> manager.registerAsynchronousCompletions()
        }
        registerExceptionHandlers()
    }

    fun register(builder: Command.Builder<out CommandSender>) {
        manager.command(builder)
    }

    /** Runs a command body as a coroutine on the main thread; errors are logged and reported. */
    fun launch(sender: CommandSender, block: suspend CoroutineScope.() -> Unit) {
        core.scope.launch(core.main) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                core.logger.log(Level.SEVERE, "Error while running a Koban command", e)
                messenger.send(sender, "command.error")
            }
        }
    }

    private fun registerExceptionHandlers() {
        val controller = manager.exceptionController()
        controller.registerHandler(InvalidSyntaxException::class.java) { ctx ->
            messenger.send(ctx.context().sender(), "command.invalid-syntax", tag("syntax", "/" + ctx.exception().correctSyntax()))
        }
        controller.registerHandler(NoPermissionException::class.java) { ctx ->
            messenger.send(ctx.context().sender(), "command.no-permission")
        }
        controller.registerHandler(InvalidCommandSenderException::class.java) { ctx ->
            messenger.send(ctx.context().sender(), "command.players-only")
        }
        controller.registerHandler(ArgumentParseException::class.java) { ctx ->
            messenger.send(ctx.context().sender(), "command.invalid-argument", tag("reason", ctx.exception().cause.message ?: "?"))
        }
        controller.registerHandler(CommandExecutionException::class.java) { ctx ->
            core.logger.log(Level.SEVERE, "Error while running a Koban command", ctx.exception().cause ?: ctx.exception())
            messenger.send(ctx.context().sender(), "command.error")
        }
    }
}
