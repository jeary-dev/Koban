package dev.jeary.koban.platform.text

import dev.jeary.koban.platform.compat.Platform
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import net.kyori.adventure.text.serializer.json.JSONOptions
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.md_5.bungee.api.ChatColor
import net.md_5.bungee.api.chat.BaseComponent
import net.md_5.bungee.api.chat.hover.content.Text
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import java.lang.reflect.Method
import java.util.logging.Logger
import net.md_5.bungee.api.chat.ClickEvent as BungeeClick
import net.md_5.bungee.api.chat.HoverEvent as BungeeHover
import net.md_5.bungee.api.chat.TextComponent as BungeeText

/**
 * Delivers a chat component to a player or the console.
 *
 * Koban's Adventure is shaded and relocated (`dev.jeary.koban.libs.kyori`), so its components are
 * different classes from Paper's own Adventure and can't be handed to Paper's `Audience` methods.
 * Each sink crosses that gap through public API only:
 *
 * - [PaperTextSink]: component → JSON → Paper's own `GsonComponentSerializer` → `Audience.sendMessage`.
 * - [BungeeTextSink]: component → BungeeCord chat components → `CommandSender.spigot().sendMessage`
 *   (Spigot's public chat API), with no JSON in between.
 * - [LegacyTextSink]: a `§`-coded string; the last resort, without hover or click.
 */
sealed interface TextSink {
    val name: String
    fun send(target: CommandSender, component: Component)

    companion object {
        /** The best sink this server supports, checked by actually sending nothing through it. */
        fun create(platform: Platform, logger: Logger): TextSink {
            if (platform == Platform.PAPER) {
                PaperTextSink.create(logger)?.let { return it }
            }
            if (Platform.classExists("net.md_5.bungee.api.chat.BaseComponent")) return BungeeTextSink
            return LegacyTextSink
        }

        /** The JSON format every supported server's parser reads (the oldest, most compatible form). */
        val json: GsonComponentSerializer = GsonComponentSerializer.builder().options(JSONOptions.compatibility()).build()

        val legacy: LegacyComponentSerializer = LegacyComponentSerializer.builder()
            .character(LegacyComponentSerializer.SECTION_CHAR)
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build()
    }
}

/**
 * Paper's native Adventure, reached by reflection on Paper's *public* API (never server
 * internals). Class names are assembled at runtime: written as one literal, the shade plugin
 * would relocate them to Koban's own copy and the lookup would find the wrong classes.
 */
class PaperTextSink private constructor(
    private val audienceClass: Class<*>,
    private val nativeSerializer: Any,
    private val deserialize: Method,
    private val serialize: Method,
    private val sendMessage: Method,
) : TextSink {

    override val name: String = "Paper (native Adventure via JSON)"

    override fun send(target: CommandSender, component: Component) {
        if (!audienceClass.isInstance(target)) {
            LegacyTextSink.send(target, component)
            return
        }
        sendMessage.invoke(target, toNative(component))
    }

    /** Koban's component as one of Paper's own components. */
    fun toNative(component: Component): Any = deserialize.invoke(nativeSerializer, TextSink.json.serialize(component))

    /** Paper's JSON for a native component: used by `/koban debug text` to prove the round trip. */
    fun nativeJson(native: Any): String = serialize.invoke(nativeSerializer, native) as String

    companion object {
        private fun kyori(vararg parts: String) = (listOf("net", "kyori", "adventure") + parts).joinToString(".")

        fun create(logger: Logger): PaperTextSink? = try {
            val loader = Bukkit::class.java.classLoader
            val componentClass = Class.forName(kyori("text", "Component"), false, loader)
            val audienceClass = Class.forName(kyori("audience", "Audience"), false, loader)
            val serializerClass = Class.forName(kyori("text", "serializer", "gson", "GsonComponentSerializer"), true, loader)
            val instance = serializerClass.getMethod("gson").invoke(null)
            val deserialize = serializerClass.getMethod("deserialize", Any::class.java)
            val serialize = serializerClass.getMethod("serialize", componentClass)
            val sendMessage = audienceClass.getMethod("sendMessage", componentClass)
            PaperTextSink(audienceClass, instance, deserialize, serialize, sendMessage).also { sink ->
                // Prove the path once at startup, so a broken server falls back instead of failing on first use.
                sink.toNative(Component.text("koban"))
            }
        } catch (e: ReflectiveOperationException) {
            logger.info("Paper's native text API isn't usable here (${e.javaClass.simpleName}); using Spigot's chat API.")
            null
        } catch (e: LinkageError) {
            logger.info("Paper's native text API isn't usable here (${e.javaClass.simpleName}); using Spigot's chat API.")
            null
        }
    }
}

/** Spigot's public BungeeCord chat API. Converts the component tree directly, with no JSON format to agree on. */
data object BungeeTextSink : TextSink {
    override val name: String = "Spigot (BungeeCord chat API)"

    override fun send(target: CommandSender, component: Component) {
        target.spigot().sendMessage(convert(component))
    }

    fun convert(component: Component): BaseComponent {
        val out: BaseComponent = when (component) {
            is TextComponent -> BungeeText(component.content())
            // Koban's messages are text only; anything else is flattened rather than dropped.
            else -> BungeeText(PlainTextComponentSerializer.plainText().serialize(component.children(emptyList())))
        }
        val style = component.style()
        style.color()?.let { out.color = ChatColor.of(it.asHexString()) }
        out.setBold(state(style.decoration(TextDecoration.BOLD)))
        out.setItalic(state(style.decoration(TextDecoration.ITALIC)))
        out.setUnderlined(state(style.decoration(TextDecoration.UNDERLINED)))
        out.setStrikethrough(state(style.decoration(TextDecoration.STRIKETHROUGH)))
        out.setObfuscated(state(style.decoration(TextDecoration.OBFUSCATED)))
        style.insertion()?.let { out.insertion = it }
        style.clickEvent()?.let { click(it) }?.let { out.clickEvent = it }
        style.hoverEvent()?.let { hover(it) }?.let { out.hoverEvent = it }
        component.children().forEach { out.addExtra(convert(it)) }
        return out
    }

    private fun state(state: TextDecoration.State): Boolean? = when (state) {
        TextDecoration.State.TRUE -> true
        TextDecoration.State.FALSE -> false
        TextDecoration.State.NOT_SET -> null
    }

    private fun click(event: ClickEvent<*>): BungeeClick? {
        val value = (event.payload() as? ClickEvent.Payload.Text)?.value() ?: return null
        val action = BungeeClick.Action.entries.firstOrNull { it.name.equals(event.action().name(), ignoreCase = true) } ?: return null
        return BungeeClick(action, value)
    }

    private fun hover(event: HoverEvent<*>): BungeeHover? {
        if (event.action() != HoverEvent.Action.SHOW_TEXT) return null
        val text = event.value() as? Component ?: return null
        return BungeeHover(BungeeHover.Action.SHOW_TEXT, Text(arrayOf(convert(text))))
    }
}

/** `§`-coded text through `CommandSender.sendMessage(String)`: works everywhere, without hover or click. */
data object LegacyTextSink : TextSink {
    override val name: String = "legacy text"

    override fun send(target: CommandSender, component: Component) {
        target.sendMessage(TextSink.legacy.serialize(component))
    }
}
