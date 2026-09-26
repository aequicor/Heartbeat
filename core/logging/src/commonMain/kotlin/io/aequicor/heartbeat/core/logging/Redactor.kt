package io.aequicor.heartbeat.core.logging

/** Last line of defence: strips well-known secret shapes from every log message. */
internal object Redactor {

    private const val SECRET_NAMES =
        "api[_-]?key|x-api-key|access[_-]?token|refresh[_-]?token|token|password|secret"

    private val rules: List<Pair<Regex, String>> = listOf(
        Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+/=-]+""") to "Bearer ***",
        Regex("""\bsk-[A-Za-z0-9_-]{8,}""") to "sk-***",
        Regex("""(?i)\b($SECRET_NAMES)(\s*[:=]\s*)\S+""") to "$1$2***",
        Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""") to "***@***",
    )

    fun redact(message: String): String = rules.fold(message) { acc, (regex, replacement) ->
        regex.replace(acc, replacement)
    }
}
