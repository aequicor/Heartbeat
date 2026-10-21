package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.security

/** Execution dialect, supplied by the launcher rather than inferred from a command name. */
internal enum class ShellDialect { Posix, Bash, Cmd, PowerShell }

/** A complete parse of the supported literal subset, or an explicit absence of structural evidence. */
internal sealed interface ShellParseResult {
    data class Parsed(val statements: List<ShellPipeline>) : ShellParseResult
    data class Unknown(val reason: String) : ShellParseResult
}

internal data class ShellPipeline(val commands: List<ShellCommand>)

/** Words retain their case. Redirections retain execution order and are not command arguments. */
internal data class ShellCommand(val words: List<String>, val redirects: List<ShellRedirection>)

internal enum class ShellRedirectionKind { Input, Output, Append }

/** A null descriptor means the dialect's default for this redirection kind. */
internal data class ShellRedirection(
    val target: String,
    val kind: ShellRedirectionKind,
    val fileDescriptor: Int? = null,
)

/**
 * Parses literal simple commands, pipelines and POSIX statement separators without executing anything.
 * This is structural evidence, never a safety verdict: command semantics, nested interpreters, actual executable
 * resolution and resources belong to the action analyzer. Unsupported expansions, control flow and syntax
 * invalidate the complete input; a parsed prefix cannot authorize its unparsed suffix.
 *
 * Cmd supports one line, literal words, complete double-quoted words, caret escapes and pipelines. Its semicolon
 * is an ordinary character, single quotes do not quote, and percent/delayed expansion is unsupported even inside
 * quotes. Embedded or escaped double quotes are deliberately unsupported because Windows program argument
 * decoding differs from cmd's own quote processing. PowerShell requires the separate native AST frontend.
 */
internal fun parseShellStructure(command: String, dialect: ShellDialect): ShellParseResult = when {
    dialect == ShellDialect.PowerShell -> ShellParseResult.Unknown("PowerShell requires its native AST parser")

    command.length > MAX_SHELL_CHARS -> ShellParseResult.Unknown("Shell input exceeds the analysis limit")

    command.any { it < ' ' && it != '\t' && it != '\n' } ->
        ShellParseResult.Unknown("Unsupported control character")

    dialect == ShellDialect.Cmd && command.any { it == '\n' || it == '%' || it == '!' } ->
        ShellParseResult.Unknown("Cmd multiline or variable expansion is unsupported")

    else -> ShellStructureParser(ShellLexer(command, dialect), dialect).parse()
}

private class ShellStructureParser(private val lexer: ShellLexer, private val dialect: ShellDialect) {
    private val statements = mutableListOf<ShellPipeline>()
    private val commands = mutableListOf<ShellCommand>()
    private val words = mutableListOf<String>()
    private val redirects = mutableListOf<ShellRedirection>()
    private var failure: String? = null
    private var pending: ShellToken.Redirect? = null
    private var afterPipe = false

    fun parse(): ShellParseResult {
        while (failure == null) {
            when (val token = lexer.next()) {
                is ShellToken.Invalid -> failure = token.reason
                is ShellToken.Word -> word(token.value)
                is ShellToken.Redirect -> redirect(token)
                ShellToken.Pipe -> pipe()
                ShellToken.Separator -> separator()
                ShellToken.End -> return finish()
            }
        }
        return ShellParseResult.Unknown(checkNotNull(failure))
    }

    private fun word(value: String) {
        val redirect = pending
        if (redirect != null) {
            if (value.isEmpty()) failure = "Empty redirection target"
            redirects += ShellRedirection(value, redirect.kind, redirect.descriptor)
            pending = null
        } else {
            if (words.isEmpty() && dialect != ShellDialect.Cmd && isShellGrammar(value)) {
                failure = "Shell control flow or assignment is unsupported"
            }
            words += value
        }
        afterPipe = false
    }

    private fun redirect(token: ShellToken.Redirect) {
        if (pending != null) failure = "Missing redirection target"
        pending = token
    }

    private fun pipe() {
        if (completeCommand()) afterPipe = true
    }

    private fun separator() {
        if (afterPipe || !completeCommand()) {
            failure = failure ?: "Empty pipeline command"
        } else {
            statements += ShellPipeline(commands.toList())
            commands.clear()
        }
    }

    private fun completeCommand(): Boolean {
        failure = when {
            pending != null -> "Missing redirection target"
            words.isEmpty() || words.first().isEmpty() -> "A command name is required"
            else -> failure
        }
        if (failure != null) return false
        commands += ShellCommand(words.toList(), redirects.toList())
        words.clear()
        redirects.clear()
        return true
    }

    private fun finish(): ShellParseResult {
        if (afterPipe || pending != null) return ShellParseResult.Unknown("Incomplete pipeline or redirection")
        if (words.isNotEmpty() || redirects.isNotEmpty()) {
            completeCommand()
        }
        if (failure == null && commands.isNotEmpty()) statements += ShellPipeline(commands.toList())
        return when {
            failure != null -> ShellParseResult.Unknown(checkNotNull(failure))

            dialect == ShellDialect.Cmd && lexer.hasCmdEscapes && statements.any { it.commands.size > 1 } ->
                ShellParseResult.Unknown("Cmd pipeline reparsing of caret escapes is unsupported")

            statements.isEmpty() -> ShellParseResult.Unknown("No command was supplied")

            else -> ShellParseResult.Parsed(statements.toList())
        }
    }
}

private sealed interface ShellToken {
    data class Word(val value: String) : ShellToken
    data class Redirect(val kind: ShellRedirectionKind, val descriptor: Int?) : ShellToken
    data class Invalid(val reason: String) : ShellToken
    data object Pipe : ShellToken
    data object Separator : ShellToken
    data object End : ShellToken
}

private class ShellLexer(private val input: String, private val dialect: ShellDialect) {
    private var position = 0
    private var failure: String? = null
    private val isCmd = dialect == ShellDialect.Cmd
    var hasCmdEscapes = false
        private set

    fun next(): ShellToken {
        while (position < input.length) {
            when {
                input[position] in " \t" -> position++
                !isCmd && input[position] == '\\' && input.getOrNull(position + 1) == '\n' -> position += 2
                else -> break
            }
        }
        if (position == input.length) return ShellToken.End
        return when (input[position]) {
            '|' -> operator(ShellToken.Pipe)
            ';', '\n' -> if (isCmd) word() else operator(ShellToken.Separator)
            '<', '>' -> redirection(null)
            else -> word()
        }
    }

    private fun operator(token: ShellToken): ShellToken {
        position++
        return if (position < input.length && input[position] in "|&;") {
            ShellToken.Invalid("Compound shell operators are unsupported")
        } else {
            token
        }
    }

    private fun word(): ShellToken {
        val word = ShellWord()
        while (position < input.length && !isBoundary(input[position]) && failure == null) {
            wordPart(word)
        }
        failure?.let { return ShellToken.Invalid(it) }
        val text = word.value.toString()
        return if (word.isBare && text.all { it in '0'..'9' } && input.getOrNull(position) in listOf('<', '>')) {
            val descriptor = text.toIntOrNull()
            if (descriptor == null || (isCmd && text.length != 1)) {
                ShellToken.Invalid("Unsupported file descriptor")
            } else {
                redirection(descriptor)
            }
        } else {
            ShellToken.Word(text)
        }
    }

    private fun wordPart(word: ShellWord) {
        val character = input[position]
        when {
            character == '"' || (character == '\'' && !isCmd) -> quotedWord(word, character)

            character == (if (isCmd) '^' else '\\') -> escapedWord(word)

            unsupported(character, word.hasPart) -> failure = "Unsupported shell expansion or control syntax"

            else -> {
                word.value.append(character)
                position++
                word.hasPart = true
            }
        }
    }

    private fun quotedWord(word: ShellWord, quote: Char) {
        if (isCmd && word.hasPart) failure = "Embedded cmd quotes are unsupported"
        quoted(word.value, quote)
        word.isBare = false
        word.hasPart = true
        if (isCmd && position < input.length && !isBoundary(input[position])) {
            failure = "Embedded cmd quotes are unsupported"
        }
    }

    private fun escapedWord(word: ShellWord) {
        val isContinuation = !isCmd && input.getOrNull(position + 1) == '\n'
        escaped(word.value, inQuotes = false)
        if (!isContinuation) {
            word.isBare = false
            word.hasPart = true
        }
    }

    private fun quoted(value: StringBuilder, quote: Char) {
        position++
        while (position < input.length && input[position] != quote && failure == null) {
            quotedPart(value, quote)
        }
        if (position == input.length) {
            failure = "Unclosed shell quote"
        } else if (failure == null) {
            position++
        }
    }

    private fun quotedPart(value: StringBuilder, quote: Char) {
        val character = input[position]
        when {
            !isCmd && quote == '"' && character in "\$`" -> failure = "Quoted expansion is unsupported"

            dialect == ShellDialect.Bash && quote == '"' && character == '!' ->
                failure = "Bash history expansion is unsupported"

            !isCmd && quote == '"' && character == '\\' -> escaped(value, inQuotes = true)

            isCmd && character == '\\' && input.getOrNull(position + 1) == '"' ->
                failure = "Ambiguous Windows argument quoting"

            else -> {
                value.append(character)
                position++
            }
        }
    }

    private fun escaped(value: StringBuilder, inQuotes: Boolean) {
        if (isCmd) hasCmdEscapes = true
        position++
        val escaped = input.getOrNull(position)
        when {
            escaped == null -> failure = "Dangling shell escape"

            isCmd && escaped in "\n\"" -> failure = "Unsupported cmd escape"

            !isCmd && escaped == '\n' -> position++

            inQuotes && escaped !in "\$`\"\\" -> value.append('\\')

            else -> {
                value.append(escaped)
                position++
            }
        }
    }

    private fun unsupported(character: Char, hasPart: Boolean): Boolean = if (isCmd) {
        character in "&()"
    } else {
        character in "&(){}\$`*?[~" || (character == '#' && !hasPart) ||
            (dialect == ShellDialect.Bash && character == '!')
    }

    private fun isBoundary(character: Char): Boolean = character in " \t|<>" || (!isCmd && character in ";\n")

    private fun redirection(descriptor: Int?): ShellToken {
        val first = input[position++]
        val second = input.getOrNull(position)
        val kind = if (first == '>' && second == '>') {
            position++
            ShellRedirectionKind.Append
        } else if (first == '<') {
            ShellRedirectionKind.Input
        } else {
            ShellRedirectionKind.Output
        }
        return if (input.getOrNull(position) in listOf('<', '>', '&', '|', '(')) {
            ShellToken.Invalid("Descriptor duplication and compound redirections are unsupported")
        } else {
            ShellToken.Redirect(kind, descriptor)
        }
    }
}

private class ShellWord {
    val value = StringBuilder()
    var isBare = true
    var hasPart = false
}

private fun isShellGrammar(word: String): Boolean {
    if (word in SHELL_GRAMMAR_WORDS) return true
    val equals = word.indexOf('=')
    if (equals <= 0) return false
    val name = word.take(equals)
    return (name.first().isLetter() || name.first() == '_') && name.all { it.isLetterOrDigit() || it == '_' }
}

private val SHELL_GRAMMAR_WORDS = setOf(
    "!", "if", "then", "else", "elif", "fi", "while", "until", "for", "in", "do", "done", "case", "esac",
    "function", "select", "time", "coproc",
)
private const val MAX_SHELL_CHARS = 65_536
