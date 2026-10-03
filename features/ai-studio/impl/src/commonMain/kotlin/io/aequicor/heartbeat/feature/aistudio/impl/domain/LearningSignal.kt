package io.aequicor.heartbeat.feature.aistudio.impl.domain

/** A frequent environment problem recognised in tool output; the agent is asked to remember its workaround. */
internal enum class LearningSignal {
    /** Text decoded or encoded with the wrong charset or console code page. */
    TextEncoding,

    /** A command written for another shell (cmd, PowerShell or POSIX). */
    ShellDialect,

    /** Windows line endings reaching a POSIX shell or interpreter. */
    LineEndings,
}

/** Signals whose signatures occur in [output]; empty for ordinary output. */
internal fun detectLearningSignals(output: String): Set<LearningSignal> = buildSet {
    if (ENCODING.any { it.containsMatchIn(output) } || output.hasReplacements()) {
        add(LearningSignal.TextEncoding)
    }
    if (SHELL.any { it.containsMatchIn(output) }) add(LearningSignal.ShellDialect)
    if (LINE_ENDINGS.any { it.containsMatchIn(output) }) add(LearningSignal.LineEndings)
}

/** Host directive asking the agent to save the workaround of [signals] if it found one. */
internal fun learningHint(signals: Set<LearningSignal>): String {
    val problems = signals.sortedBy { it.ordinal }.joinToString("; ") {
        when (it) {
            LearningSignal.TextEncoding -> "text encoding or console code page errors (mojibake, codec failures)"
            LearningSignal.ShellDialect -> "commands written for the wrong shell"
            LearningSignal.LineEndings -> "CRLF line endings breaking a script"
        }
    }
    return "Heartbeat noticed in the previous turn's tool output: $problems. If you found a reliable workaround, " +
        "call the remember tool once with a short general instruction (or a model instruction if it is specific " +
        "to you) so later sessions avoid it; if not, continue with the user's request."
}

private const val MIN_REPLACEMENTS = 3

private const val REPLACEMENT = '\uFFFD'

private fun String.hasReplacements(): Boolean {
    var count = 0
    return any { it == REPLACEMENT && ++count >= MIN_REPLACEMENTS }
}

private val ENCODING = listOf(
    Regex("""Unicode(?:Encode|Decode)Error"""),
    Regex("""'charmap' codec can't (?:en|de)code"""),
    Regex("""codec can't (?:en|de)code (?:character|byte)"""),
    Regex("""MalformedInputException"""),
    Regex("""unmappable character"""),
    Regex("""[Ii]nvalid byte sequence"""),
    Regex("""(?:Ð[\u0080-¿]){3,}"""),
    Regex("""(?:Ã[\u0080-¿]){2,}"""),
    Regex("""(?:â€[\u0080-¿‘-›]){2,}"""),
)

private val SHELL = listOf(
    Regex("""is not recognized as an internal or external command"""),
    Regex("""The term '[^']+' is not recognized as (?:the |a )?name of a cmdlet"""),
    Regex("""The token '&&' is not a valid statement separator"""),
    Regex("""'(?:ls|cat|grep|rm|export)' is not recognized"""),
)

private val LINE_ENDINGS = listOf(
    Regex("""\r: command not found"""),
    Regex("""bad interpreter: [^\n]*\^M"""),
    Regex("""\$'\\r': command not found"""),
)
