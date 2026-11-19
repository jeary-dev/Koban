package dev.jeary.koban.platform

import dev.jeary.koban.config.Messages
import dev.jeary.koban.platform.text.TextSink
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.command.CommandSender

/**
 * All player-facing text goes through here: MiniMessage templates from messages.yml, rendered
 * with Koban's own Adventure and delivered through a [TextSink] (see there for how that works on
 * Paper and Spigot without touching the server's own Adventure classes).
 *
 * Values from players (names, report details, notes) are always inserted with [tag], which
 * escapes them, so nobody can put MiniMessage tags into someone else's chat.
 */
class Messenger(@Volatile var messages: Messages, val sink: TextSink) {

    val miniMessage: MiniMessage = MiniMessage.miniMessage()

    fun parse(raw: String, vararg tags: TagResolver): Component =
        miniMessage.deserialize(raw, TagResolver.resolver(Placeholder.parsed("prefix", messages.prefix), *tags))

    fun component(key: String, vararg tags: TagResolver): Component = parse(messages.raw(key), *tags)

    /** Sends the message at [key]. A message set to '' in messages.yml is switched off. */
    fun send(target: CommandSender, key: String, vararg tags: TagResolver) {
        val raw = messages.raw(key)
        if (raw.isBlank()) return
        sink.send(target, parse(raw, *tags))
    }

    fun send(target: CommandSender, component: Component) = sink.send(target, component)

    fun isEnabled(key: String): Boolean = messages.raw(key).isNotBlank()

    /** MiniMessage → legacy string for item names, lore and inventory titles (italics off). */
    fun itemText(raw: String, vararg tags: TagResolver): String =
        TextSink.legacy.serialize(parse(raw, *tags).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE))

    fun title(raw: String, vararg tags: TagResolver): String = TextSink.legacy.serialize(parse(raw, *tags))

    /** The text of a message without any formatting, e.g. for a console line. */
    fun plain(key: String, vararg tags: TagResolver): String =
        net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(component(key, *tags))

    companion object {
        /** A value inserted as plain text: tags inside it are shown literally, never parsed. */
        fun tag(name: String, value: Any?): TagResolver = Placeholder.unparsed(name, value?.toString() ?: "")

        fun component(name: String, value: Component): TagResolver = Placeholder.component(name, value)

        /** Admin-written MiniMessage (from config), parsed as formatting. Never for player input. */
        fun parsed(name: String, miniMessage: String): TagResolver = Placeholder.parsed(name, miniMessage)
    }
}
