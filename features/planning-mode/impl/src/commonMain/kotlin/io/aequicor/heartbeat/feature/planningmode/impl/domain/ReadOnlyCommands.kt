package io.aequicor.heartbeat.feature.planningmode.impl.domain

/**
 * Whether [command] provably only reads: every pipeline segment runs an allowlisted program with options that
 * cannot write files, run other programs or start an editor.
 *
 * The words the classifier checks must be exactly the words the program receives, so any syntax the shell could
 * expand is not provably read-only: `$`, `{`, `}`, `*`, `?`, `[`, `]`, `~`, `(`, `)`, `!`, `^` and `#` (a comment, or
 * a glob operator under zsh `EXTENDED_GLOB`) outside quotes,
 * `$` and backticks inside double quotes, backslash-newline continuations, command or process substitution,
 * subshells, background jobs, here-documents and environment prefixes. Redirections are allowed only to
 * `/dev/null`, from plain files and as `>&1` / `>&2`. Programs given by path, unknown programs and options outside
 * each program's allowlist (including abbreviations of forbidden GNU long options) need a decision; `xargs` is not
 * allowed since it runs arbitrary programs. PowerShell is never classified.
 * A false result only means the command needs a decision; it is not a judgement of its safety.
 */
internal fun isReadOnlyCommand(command: String, shell: ShellKind = ShellKind.Posix): Boolean {
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
        "ls", "pwd", "cd", "stat", "du", "df", "which", "type", "echo", "printf", "basename", "dirname",
        "realpath", "readlink", "whoami", "uname", "true", "false", "test", "md5sum", "shasum", "sha256sum",
        "cat", "head", "wc", "nl", "cut", "tr", "cmp", "jq", "grep", "egrep", "fgrep",
    ).forEach { this[it] = { true } }
    this["file"] = { !it.hasShortFlag("C") && !it.hasLongOption("compile") }
    this["tail"] = { !it.hasShortFlag("fF") && !it.hasLongOption("follow") }
    this["rg"] = { arguments ->
        !arguments.hasShortFlag("z") &&
            arguments.none { option -> RG_FORBIDDEN.any { name -> option.startsWith("--$name") } }
    }
    val fd: (List<String>) -> Boolean = {
        !it.hasShortFlag("xX") && !it.hasLongOption("exec") && !it.hasLongOption("exec-batch")
    }
    this["fd"] = fd
    this["fdfind"] = fd
    this["tree"] = { isWhitelisted(it, flags = TREE_FLAGS, valued = "LIP") }
    this["sort"] = { isWhitelisted(it, flags = SORT_FLAGS, valued = "kt") }
    this["uniq"] = { it.count { argument -> !argument.startsWith("-") } <= 1 }
    this["diff"] = { !it.hasLongOption("output") }
    this["find"] = { it.none(FIND_ACTIONS::contains) }
    this["sed"] = ::isReadOnlySed
    this["env"] = { it.isEmpty() }
    this["date"] = { !it.hasShortFlag("s") && !it.hasLongOption("set") }
    this["git"] = ::isReadOnlyGit
}

/** Long options of `rg` that run programs or decompress through them. */
private val RG_FORBIDDEN = listOf("pre", "hostname-bin", "search-zip")
private const val TREE_FLAGS = "adfilpsughDCFQNrtvU"
private const val SORT_FLAGS = "bdfghiMnRrsuVz"

private val FIND_ACTIONS = setOf(
    "-exec", "-execdir", "-ok", "-okdir", "-delete", "-fprint", "-fprint0", "-fprintf", "-fls",
)

/** Whether a short option cluster such as `-rn` contains one of [flags]. */
private fun List<String>.hasShortFlag(flags: String): Boolean =
    any { it.startsWith("-") && !it.startsWith("--") && it.drop(1).any(flags::contains) }

/**
 * Whether an argument is the GNU long option `--[option]` or any abbreviation of it, which `getopt_long` and git
 * accept when unambiguous, with or without an attached `=value`.
 */
private fun List<String>.hasLongOption(option: String): Boolean = any { argument ->
    val name = argument.takeIf { it.startsWith("--") }?.drop(2)?.substringBefore('=')
    !name.isNullOrEmpty() && option.startsWith(name)
}

/**
 * Whether every option is a short cluster of [flags], with a letter of [valued] taking the rest of the cluster or
 * the next word as its value. Long options are never allowed.
 */
private fun isWhitelisted(arguments: List<String>, flags: String, valued: String): Boolean {
    var index = 0
    var isAllowed = true
    while (isAllowed && index < arguments.size) {
        val argument = arguments[index]
        index += 1
        if (!argument.startsWith("-") || argument == "-") continue
        val cluster = argument.drop(1)
        val valuedAt = cluster.indexOfFirst(valued::contains)
        val letters = if (valuedAt >= 0) cluster.take(valuedAt) else cluster
        isAllowed = !argument.startsWith("--") && letters.all(flags::contains)
        if (valuedAt == cluster.lastIndex) index += 1
    }
    return isAllowed && index <= arguments.size
}

/** `sed -n` with a single line-range print script, such as `sed -n '10,40p' file`. */
private fun isReadOnlySed(arguments: List<String>): Boolean {
    val options = arguments.filter { it.startsWith("-") }
    val script = arguments.firstOrNull { !it.startsWith("-") }
    val areOptionsAllowed = options.all { it.length > 1 && !it.startsWith("--") && it.drop(1).all(SED_FLAGS::contains) }
    val isQuiet = options.any { 'n' in it }
    return areOptionsAllowed && isQuiet && script != null && SED_PRINT.matches(script)
}

private const val SED_FLAGS = "nErsuz"
private val SED_PRINT = Regex("""\d+(,(\d+|\$))?p""")

private fun isReadOnlyGit(arguments: List<String>): Boolean {
    val start = gitSubcommandIndex(arguments)
    val subcommand = start?.let(arguments::getOrNull)
    val rule = subcommand?.let(GIT_RULES::get)
    val rest = arguments.drop((start ?: 0) + 1)
    val isForbidden = rest.hasShortFlag("O") || GIT_FORBIDDEN_OPTIONS.any(rest::hasLongOption) ||
        rest.any { option -> GIT_FORBIDDEN_OPTIONS.any { name -> option.startsWith("--$name") } }
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
        "cat-file", "rev-list", "merge-base", "whatchanged",
    ).forEach { this[it] = { _, _ -> true } }
    this["reflog"] = { _, operands -> operands.isEmpty() || operands.first() == "show" }
    this["branch"] = { options, operands -> operands.isEmpty() && options.all(GIT_BRANCH_LISTING::contains) }
    this["tag"] = { options, operands -> operands.isEmpty() && options.all { it == "-l" || it == "--list" } }
    this["remote"] = { options, operands ->
        val isListing = operands.isEmpty() && options.all { it == "-v" || it == "--verbose" }
        isListing || operands.firstOrNull() in setOf("show", "get-url")
    }
    this["stash"] = { _, operands -> operands.firstOrNull() in setOf("list", "show") }
    this["config"] = { options, _ -> options.isNotEmpty() && options.all(GIT_CONFIG_READS::contains) }
}

/** Long options (without `--`) that write files or run programs; their abbreviations are forbidden too. */
private val GIT_FORBIDDEN_OPTIONS = listOf("output", "ext-diff", "open-files-in-pager", "exec", "upload-pack")
private val GIT_BRANCH_LISTING = setOf(
    "-a", "-r", "-v", "-vv", "--all", "--remotes", "--list", "-l", "--show-current", "--verbose",
)
private val GIT_CONFIG_READS = setOf("--get", "--get-all", "--get-regexp", "--list", "-l", "--show-origin")

/**
 * Splits a command into the words of its pipeline and list segments, or rejects shell syntax that is not provably
 * read-only. Quotes and escapes are honored; redirections to `/dev/null`, from files and between standard streams
 * are dropped. Characters the shell could expand reject the command, so each word is what the program receives.
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
        isExpansion(char) -> false
        char in " \t" -> advance(1) { endWord() }
        char in "\n;" -> separator(length = 1, isOperator = false)
        char == '|' -> separator(length = if (next() == '|') 2 else 1, isOperator = true)
        char == '&' && next() == '&' -> separator(length = 2, isOperator = true)
        char in "<>&" -> redirection()
        else -> advance(1) { word.append(char) }
    }

    private fun next(): Char? = command.getOrNull(index + 1)

    /** An unquoted expansion, subshell, history or extended glob character, or a comment. */
    private fun isExpansion(char: Char): Boolean = char in EXPANSIONS

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
        var isAccepted = true
        while (isAccepted && cursor < command.length && command[cursor] != '"') {
            val char = command[cursor]
            val escapedChar = command.getOrNull(cursor + 1).takeIf { char == '\\' }
            isAccepted = char !in DOUBLE_QUOTE_EXPANSIONS && escapedChar != '\n'
            if (escapedChar in DOUBLE_QUOTE_ESCAPES) cursor += 1
            word.append(command[cursor])
            cursor += 1
        }
        isQuoted = true
        index = cursor + 1
        return isAccepted && cursor < command.length
    }

    private fun escaped(): Boolean {
        val escapedChar = next()?.takeIf { it != '\n' }
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

/**
 * Unquoted characters that start parameter, brace, glob, tilde or history expansion, a subshell or a comment; `^` and
 * `#` anywhere in a word are glob operators under zsh `EXTENDED_GLOB`.
 */
private const val EXPANSIONS = "\${}*?[]~()!^#"

/** Characters that expand inside double quotes; rejected even when escaped. */
private const val DOUBLE_QUOTE_EXPANSIONS = "\$`"
private val DOUBLE_QUOTE_ESCAPES = setOf('"', '\\')

/** Characters that end a word after a redirection. */
private const val WORD_END = " \t\n;|&<>"

/** The index after an allowed redirection starting at [start], or null for any other redirection. */
private fun redirectionEnd(command: String, start: Int): Int? {
    val operator = REDIRECTIONS.firstOrNull { command.startsWith(it, start) }
    val afterOperator = start + (operator?.length ?: 0)
    if (operator == null || operator.endsWith('&')) {
        val stream = command.getOrNull(afterOperator)
        val following = command.getOrNull(afterOperator + 1)
        val isStream = (stream == '1' || stream == '2') && (following == null || following in WORD_END)
        return afterOperator.plus(1).takeIf { operator != null && isStream }
    }
    val targetStart = (afterOperator until command.length).firstOrNull { command[it] != ' ' } ?: command.length
    val end = (targetStart until command.length).firstOrNull { command[it] in WORD_END } ?: command.length
    return end.takeIf { isAllowedTarget(operator, command.substring(targetStart, end)) }
}

private fun isAllowedTarget(operator: String, target: String): Boolean = when (operator) {
    "<" -> target.isNotEmpty() && target.none { it in "'\"\\" || it in EXPANSIONS } &&
        (!target.startsWith("/dev/") || target == NULL_DEVICE)

    else -> target == NULL_DEVICE
}

private const val NULL_DEVICE = "/dev/null"

/** Longest first, so `>>` is not read as `>`. `<<` here-documents are not allowed. */
private val REDIRECTIONS = listOf("&>>", "&>", ">>", ">&", ">", "<")
