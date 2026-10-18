package io.aequicor.heartbeat.feature.planningmode.api

/**
 * Whether [command] provably only reads: every pipeline segment runs an allowlisted program with options that
 * cannot write files, run other programs or start an editor. Unknown programs, command or process substitution,
 * subshells, background jobs, here-documents, environment prefixes, redirections other than to `/dev/null` or
 * between standard streams and program paths are not provably read-only. PowerShell is never classified.
 * A false result only means the command needs a decision; it is not a judgement of its safety.
 */
public fun isReadOnlyCommand(command: String, shell: ShellKind = ShellKind.Posix): Boolean {
    val isCandidate = shell == ShellKind.Posix && command.isNotBlank() && command.length <= MAX_COMMAND_CHARS &&
        FORBIDDEN_FRAGMENTS.none { it in command }
    val segments = if (isCandidate) ShellLexer(command).segments() else null
    return !segments.isNullOrEmpty() && segments.all(::isReadOnlyInvocation)
}

private const val MAX_COMMAND_CHARS = 4_000
private val FORBIDDEN_FRAGMENTS = listOf("`", "$(", "<(", ">(")

private fun isReadOnlyInvocation(words: List<String>): Boolean {
    val program = words.firstOrNull()?.takeIf { '=' !in it && '/' !in it }
    val rule = program?.let(PROGRAM_RULES::get)
    return rule?.invoke(words.drop(1)) == true
}

private val PROGRAM_RULES: Map<String, (List<String>) -> Boolean> = buildMap {
    listOf(
        "ls", "pwd", "cd", "stat", "file", "du", "df", "which", "type", "echo", "printf", "basename", "dirname",
        "realpath", "readlink", "whoami", "uname", "true", "false", "test", "md5sum", "shasum", "sha256sum",
        "cat", "head", "wc", "nl", "cut", "tr", "cmp", "jq", "grep", "egrep", "fgrep",
    ).forEach { this[it] = { true } }
    this["tail"] = { !it.hasShortFlag("fF") && it.none { option -> option.startsWith("--follow") } }
    this["rg"] = { it.none { option -> option.startsWith("--pre") } }
    val fd: (List<String>) -> Boolean = { !it.hasShortFlag("xX") && it.none { option -> option.startsWith("--exec") } }
    this["fd"] = fd
    this["fdfind"] = fd
    val output: (List<String>) -> Boolean = {
        !it.hasShortFlag(
            "o",
        ) && it.none { option -> option.startsWith("--output") }
    }
    this["tree"] = output
    this["sort"] = output
    this["uniq"] = { it.count { argument -> !argument.startsWith("-") } <= 1 }
    this["diff"] = { it.none { option -> option.startsWith("--output") } }
    this["find"] = { it.none(FIND_ACTIONS::contains) }
    this["sed"] = ::isReadOnlySed
    this["env"] = { it.isEmpty() }
    this["date"] = { !it.hasShortFlag("s") && it.none { option -> option.startsWith("--set") } }
    this["xargs"] = ::isReadOnlyXargs
    this["git"] = ::isReadOnlyGit
}

private val FIND_ACTIONS = setOf(
    "-exec", "-execdir", "-ok", "-okdir", "-delete", "-fprint", "-fprint0", "-fprintf", "-fls",
)

/** Whether a short option cluster such as `-rn` contains one of [flags]. */
private fun List<String>.hasShortFlag(flags: String): Boolean =
    any { it.startsWith("-") && !it.startsWith("--") && it.drop(1).any(flags::contains) }

/** `sed -n` with a line-range print script only, such as `sed -n '10,40p' file`. */
private fun isReadOnlySed(arguments: List<String>): Boolean {
    val script = arguments.firstOrNull { !it.startsWith("-") }
    val isPrintOnly = "-n" in arguments && script != null && SED_PRINT.matches(script)
    return isPrintOnly && arguments.none { it.startsWith("-i") || it.startsWith("--in-place") || it in SED_SCRIPTS }
}

private val SED_PRINT = Regex("""\d+(,(\d+|\$))?p""")
private val SED_SCRIPTS = setOf("-e", "-f", "--expression", "--file")

/** `xargs` with its own options followed by a read-only invocation. */
private fun isReadOnlyXargs(arguments: List<String>): Boolean {
    var index = 0
    while (index < arguments.size && arguments[index].startsWith("-")) {
        index += if (arguments[index] in XARGS_VALUED) 2 else 1
    }
    val invocation = arguments.drop(index)
    return invocation.firstOrNull().let { it != null && it != "xargs" } && isReadOnlyInvocation(invocation)
}

private val XARGS_VALUED = setOf("-I", "-n", "-P", "-d", "-L", "-s", "-E")

private fun isReadOnlyGit(arguments: List<String>): Boolean {
    val start = gitSubcommandIndex(arguments)
    val subcommand = start?.let(arguments::getOrNull)
    val rule = subcommand?.let(GIT_RULES::get)
    val rest = arguments.drop((start ?: 0) + 1)
    val isForbidden = rest.any { option -> GIT_FORBIDDEN_OPTIONS.any(option::startsWith) }
    return rule != null && !isForbidden && rule(rest.filter { it.startsWith("-") }, rest.filter { !it.startsWith("-") })
}

/** Index of the git subcommand after the global options that cannot change behavior, or null for any other. */
private fun gitSubcommandIndex(arguments: List<String>): Int? {
    var index = 0
    while (index < arguments.size && arguments[index].startsWith("-")) {
        index += GIT_GLOBAL_OPTIONS[arguments[index]] ?: return null
    }
    return index
}

private val GIT_GLOBAL_OPTIONS = mapOf("--no-pager" to 1, "-C" to 2)

private val GIT_RULES: Map<String, (options: List<String>, operands: List<String>) -> Boolean> = buildMap {
    listOf(
        "status", "log", "diff", "show", "blame", "rev-parse", "ls-files", "ls-tree", "grep", "describe", "shortlog",
        "cat-file", "rev-list", "merge-base", "reflog", "whatchanged",
    ).forEach { this[it] = { _, _ -> true } }
    this["branch"] = { options, operands -> operands.isEmpty() && options.all(GIT_BRANCH_LISTING::contains) }
    this["tag"] = { options, operands -> operands.isEmpty() && options.all { it == "-l" || it == "--list" } }
    this["remote"] = { options, operands ->
        val isListing = operands.isEmpty() && options.all { it == "-v" || it == "--verbose" }
        isListing || operands.firstOrNull() in setOf("show", "get-url")
    }
    this["stash"] = { _, operands -> operands.firstOrNull() in setOf("list", "show") }
    this["config"] = { options, _ -> options.isNotEmpty() && options.all(GIT_CONFIG_READS::contains) }
}

private val GIT_FORBIDDEN_OPTIONS = listOf(
    "--output",
    "--ext-diff",
    "--open-files-in-pager",
    "-O",
    "--exec",
    "--upload-pack",
)
private val GIT_BRANCH_LISTING = setOf(
    "-a", "-r", "-v", "-vv", "--all", "--remotes", "--list", "-l", "--show-current", "--verbose",
)
private val GIT_CONFIG_READS = setOf("--get", "--get-all", "--get-regexp", "--list", "-l", "--show-origin")

/**
 * Splits a command into the words of its pipeline and list segments, or rejects shell syntax that is not provably
 * read-only. Quotes and escapes are honored; redirections to `/dev/null`, from files and between standard streams
 * are dropped.
 */
private class ShellLexer(private val command: String) {
    private val segments = mutableListOf<List<String>>()
    private val words = mutableListOf<String>()
    private val word = StringBuilder()
    private var isQuoted = false
    private var isOperandPending = false
    private var index = 0

    /** The segments, or null for rejected syntax. */
    fun segments(): List<List<String>>? {
        var isAccepted = true
        while (isAccepted && index < command.length) isAccepted = step(command[index])
        return segments.takeIf { isAccepted && endSegment(isFinal = true) }
    }

    private fun step(char: Char): Boolean = when {
        char == '\'' -> singleQuoted()
        char == '"' -> doubleQuoted()
        char == '\\' -> escaped()
        char in " \t" -> advance(1) { endWord() }
        char in "\n;" -> separator(length = 1, isOperator = false)
        char == '|' -> separator(length = if (next() == '|') 2 else 1, isOperator = true)
        char == '&' && next() == '&' -> separator(length = 2, isOperator = true)
        char in "<>&" -> redirection()
        char in "(){}#" && word.isEmpty() -> false
        else -> advance(1) { word.append(char) }
    }

    private fun next(): Char? = command.getOrNull(index + 1)

    private inline fun advance(length: Int, action: () -> Unit): Boolean {
        action()
        index += length
        return true
    }

    private fun singleQuoted(): Boolean {
        val end = command.indexOf('\'', index + 1)
        if (end >= 0) {
            word.append(command, index + 1, end)
            isQuoted = true
            index = end + 1
        }
        return end >= 0
    }

    private fun doubleQuoted(): Boolean {
        var cursor = index + 1
        while (cursor < command.length && command[cursor] != '"') {
            val isEscape = command[cursor] == '\\' && command.getOrNull(cursor + 1) in DOUBLE_QUOTE_ESCAPES
            if (isEscape) cursor += 1
            word.append(command[cursor])
            cursor += 1
        }
        isQuoted = true
        index = cursor + 1
        return cursor < command.length
    }

    private fun escaped(): Boolean {
        val escapedChar = next()
        if (escapedChar != null) word.append(escapedChar)
        index += 2
        return escapedChar != null
    }

    private fun separator(length: Int, isOperator: Boolean): Boolean {
        val isFinal = !isOperator && command.substring(index + length).isBlank()
        val isAccepted = endSegment(isFinal)
        isOperandPending = isOperator
        index += length
        return isAccepted
    }

    private fun redirection(): Boolean {
        val descriptor = word.toString()
        val isDescriptor = descriptor.isEmpty() || (!isQuoted && descriptor.all(Char::isDigit))
        word.clear()
        val end = if (isDescriptor) redirectionEnd(command, index) else null
        if (end != null) index = end
        return end != null
    }

    private fun endWord() {
        if (word.isNotEmpty() || isQuoted) words += word.toString()
        word.clear()
        isQuoted = false
    }

    private fun endSegment(isFinal: Boolean): Boolean {
        endWord()
        if (words.isEmpty()) return isFinal && segments.isNotEmpty() && !isOperandPending
        segments += words.toList()
        words.clear()
        isOperandPending = false
        return true
    }
}

private val DOUBLE_QUOTE_ESCAPES = setOf('"', '\\', '$')

/** The index after an allowed redirection starting at [start], or null for any other redirection. */
private fun redirectionEnd(command: String, start: Int): Int? {
    val operator = REDIRECTIONS.firstOrNull { command.startsWith(it, start) }
    val afterOperator = start + (operator?.length ?: 0)
    if (operator == null || operator.endsWith('&')) {
        val stream = command.getOrNull(afterOperator)
        return afterOperator.plus(1).takeIf { operator != null && (stream == '1' || stream == '2') }
    }
    val targetStart = (afterOperator until command.length).firstOrNull { command[it] != ' ' } ?: command.length
    val end = (targetStart until command.length).firstOrNull { command[it] in " \t\n;|&<>" } ?: command.length
    return end.takeIf { isAllowedTarget(operator, command.substring(targetStart, end)) }
}

private fun isAllowedTarget(operator: String, target: String): Boolean = when (operator) {
    "<" -> target.isNotEmpty() && target.none { it in "'\"\\" } && (
        !target.startsWith(
            "/dev/",
        ) || target == NULL_DEVICE
    )

    else -> target == NULL_DEVICE
}

private const val NULL_DEVICE = "/dev/null"

/** Longest first, so `>>` is not read as `>`. `<<` here-documents are not allowed. */
private val REDIRECTIONS = listOf("&>>", "&>", ">>", ">&", ">", "<")
