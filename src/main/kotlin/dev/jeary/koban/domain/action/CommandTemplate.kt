package dev.jeary.koban.domain.action

import java.util.UUID

/** The placeholders an action template can use, and how each value is made safe. */
enum class Placeholder(val key: String, val description: String) {
    PLAYER("player", "the reported player's name (checked against Minecraft's name rules)"),
    UUID("uuid", "the reported player's UUID"),
    REASON("reason", "the reason staff entered (escaped)"),
    CATEGORY("category", "the report's category name (escaped)"),
    REPORT("report", "the report number"),
    STAFF("staff", "the staff member's name (escaped)"),
    SERVER("server", "this server's name from config.yml (escaped)"),
    ;

    companion object {
        private val byKey = entries.associateBy { it.key }
        fun of(key: String): Placeholder? = byKey[key]
    }
}

/** The values for one run of a template. Raw: [CommandTemplate.render] validates and escapes them. */
data class TemplateValues(
    val player: String,
    val uuid: UUID,
    val reason: String,
    val category: String,
    val report: Long,
    val staff: String,
    val server: String,
)

/**
 * A console command with placeholders, such as `tempban {player} 7d {reason} (report #{report})`.
 *
 * The template is parsed once, when config loads, into literal text and placeholder slots, and
 * rendering only ever fills slots: a value can't add a slot, end the command or start another.
 * Unknown placeholders and unsafe literal text are config errors, reported by [parse].
 */
class CommandTemplate private constructor(val source: String, private val parts: List<Part>) {

    private sealed interface Part {
        data class Literal(val text: String) : Part
        data class Slot(val placeholder: Placeholder) : Part
    }

    sealed interface Parsed {
        data class Ok(val template: CommandTemplate) : Parsed
        data class Invalid(val problems: List<String>) : Parsed
    }

    sealed interface Rendered {
        data class Ok(val command: String) : Rendered
        data class Refused(val reason: String) : Rendered
    }

    val placeholders: Set<Placeholder> = parts.filterIsInstance<Part.Slot>().map { it.placeholder }.toSet()

    fun render(
        values: TemplateValues,
        allowedNamePrefixes: Set<Char> = emptySet(),
        reasonFallback: String = "No reason given",
    ): Rendered {
        if (Placeholder.PLAYER in placeholders && !MinecraftName.isValid(values.player, allowedNamePrefixes)) {
            return Rendered.Refused("\"${values.player.take(32)}\" isn't a valid Minecraft name")
        }
        if (values.report < 0) return Rendered.Refused("invalid report number")
        val out = StringBuilder()
        for (part in parts) {
            when (part) {
                is Part.Literal -> out.append(part.text)
                is Part.Slot -> out.append(
                    when (part.placeholder) {
                        Placeholder.PLAYER -> values.player
                        Placeholder.UUID -> values.uuid.toString()
                        Placeholder.REASON -> PlaceholderEscaper.text(values.reason, MAX_REASON).ifEmpty {
                            PlaceholderEscaper.text(reasonFallback, MAX_REASON)
                        }
                        Placeholder.CATEGORY -> PlaceholderEscaper.text(values.category, MAX_SHORT)
                        Placeholder.REPORT -> values.report.toString()
                        Placeholder.STAFF -> PlaceholderEscaper.text(values.staff, MAX_SHORT)
                        Placeholder.SERVER -> PlaceholderEscaper.text(values.server, MAX_SHORT)
                    },
                )
            }
        }
        val command = out.toString().replace(WHITESPACE, " ").trim()
        return if (command.isEmpty()) Rendered.Refused("the command is empty") else Rendered.Ok(command)
    }

    override fun toString(): String = source

    companion object {
        const val MAX_REASON = 200
        const val MAX_SHORT = 48

        private val SLOT = Regex("""\{([a-z_]+)}""")
        private val WHITESPACE = Regex("""\s+""")

        /** Parses an admin-written template, collecting every problem. */
        fun parse(source: String): Parsed {
            val problems = ArrayList<String>()
            val text = source.trim().removePrefix("/")
            if (text.isEmpty()) problems += "the command is empty"
            if (text.any { it == '\n' || it == '\r' || it.isISOControl() }) {
                problems += "it contains a line break or control character (one action runs one command)"
            }
            if ('§' in text) problems += "it contains a § colour code"

            val parts = ArrayList<Part>()
            var last = 0
            for (match in SLOT.findAll(text)) {
                if (match.range.first > last) parts += Part.Literal(text.substring(last, match.range.first))
                val key = match.groupValues[1]
                val placeholder = Placeholder.of(key)
                if (placeholder == null) {
                    problems += "unknown placeholder {$key} (known: ${Placeholder.entries.joinToString { "{${it.key}}" }})"
                } else {
                    parts += Part.Slot(placeholder)
                }
                last = match.range.last + 1
            }
            if (last < text.length) parts += Part.Literal(text.substring(last))

            val literalText = parts.filterIsInstance<Part.Literal>().joinToString("") { it.text }
            if ('{' in literalText || '}' in literalText) {
                problems += "it has a stray { or } (placeholders look like {player})"
            }
            if (parts.firstOrNull() !is Part.Literal) {
                problems += "it must start with the command name, not a placeholder"
            }
            return if (problems.isEmpty()) Parsed.Ok(CommandTemplate(text, parts)) else Parsed.Invalid(problems)
        }
    }
}
