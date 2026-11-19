package dev.jeary.koban.config

/**
 * A human-readable configuration problem: which file, which YAML path, and what is wrong.
 * Rendered as `config.yml → storage.mysql.port: expected a whole number ...`.
 */
data class ConfigError(
    val file: String,
    val path: String,
    val message: String,
    val severity: Severity = Severity.ERROR,
) {
    enum class Severity { WARNING, ERROR }

    val isError: Boolean get() = severity == Severity.ERROR

    override fun toString(): String =
        if (path.isEmpty()) "$file: $message" else "$file → $path: $message"
}

/** The outcome of parsing something from config. */
sealed interface ValidationResult<out T> {

    /** Parsed successfully; [warnings] are worth showing but did not prevent loading. */
    data class Valid<T>(val value: T, val warnings: List<ConfigError> = emptyList()) : ValidationResult<T>

    data class Invalid(val errors: List<ConfigError>) : ValidationResult<Nothing>

    companion object {
        /** Valid when [problems] holds no errors, invalid otherwise. */
        fun <T> of(problems: List<ConfigError>, value: () -> T): ValidationResult<T> =
            if (problems.any { it.isError }) Invalid(problems) else Valid(value(), problems)
    }
}

/** All problems carried by a result, errors and warnings alike. */
val ValidationResult<*>.problems: List<ConfigError>
    get() = when (this) {
        is ValidationResult.Valid -> warnings
        is ValidationResult.Invalid -> errors
    }
