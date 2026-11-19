package dev.jeary.koban.domain.evidence

/**
 * Decides which commands may be kept as evidence. Commands that can carry a password or other
 * secret are never recorded. [ALWAYS_SENSITIVE] can be added to in config but not reduced.
 *
 * Matching uses the command's label only, so `/LOGIN hunter2`, `/authme:login hunter2` and
 * `/l hunter2` are all caught.
 */
class CommandRedactor(extra: Collection<String> = emptyList()) {

    val sensitive: Set<String> = ALWAYS_SENSITIVE + extra.map(::normalise).filter { it.isNotEmpty() }

    /** True when [commandLine] (with or without the leading slash) may be recorded. */
    fun mayRecord(commandLine: String): Boolean {
        val label = labelOf(commandLine)
        return label.isNotEmpty() && label !in sensitive
    }

    companion object {
        /** Login plugins (AuthMe, nLogin, LoginSecurity, OpeNLogin, JPremium, …) and account commands. */
        val ALWAYS_SENSITIVE: Set<String> = setOf(
            "login", "l", "log", "logon",
            "register", "reg",
            "unregister", "unreg",
            "changepassword", "changepass", "changepw", "cp",
            "setpassword", "setpass", "password", "passwd", "pass",
            "premium", "cracked",
            "2fa", "totp", "otp",
            "email", "mail", "recovery",
            "auth", "authme", "nlogin", "loginsecurity", "jpremium", "openlogin",
        )

        /** `/AuthMe:Login pw` → `login`. */
        fun labelOf(commandLine: String): String =
            normalise(commandLine.trimStart().removePrefix("/").substringBefore(' '))

        private fun normalise(label: String): String =
            label.trim().removePrefix("/").substringAfterLast(':').lowercase()
    }
}
