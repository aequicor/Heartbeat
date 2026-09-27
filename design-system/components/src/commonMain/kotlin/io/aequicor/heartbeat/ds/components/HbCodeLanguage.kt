package io.aequicor.heartbeat.ds.components

internal enum class HbCodeLanguage { Kotlin, Java, JavaScript, Json, Python, Shell }

internal fun hbCodeLanguage(language: String?): HbCodeLanguage? = when (languageToken(language)) {
    "kotlin", "kt", "kts" -> HbCodeLanguage.Kotlin
    "java" -> HbCodeLanguage.Java
    "javascript", "js", "jsx", "typescript", "ts", "tsx" -> HbCodeLanguage.JavaScript
    "json" -> HbCodeLanguage.Json
    "python", "py" -> HbCodeLanguage.Python
    "shell", "sh", "bash" -> HbCodeLanguage.Shell
    else -> null
}

private fun languageToken(language: String?): String? = language?.trim()?.takeWhile { !it.isWhitespace() }?.lowercase()

internal val HbCodeLanguage.keywords: Set<String>
    get() = when (this) {
        HbCodeLanguage.Kotlin -> KotlinKeywords
        HbCodeLanguage.Java -> JavaKeywords
        HbCodeLanguage.JavaScript -> JavaScriptKeywords
        HbCodeLanguage.Json -> JsonKeywords
        HbCodeLanguage.Python -> PythonKeywords
        HbCodeLanguage.Shell -> ShellKeywords
    }

internal val HbCodeLanguage.hasSlashComments: Boolean
    get() = this == HbCodeLanguage.Kotlin || this == HbCodeLanguage.Java || this == HbCodeLanguage.JavaScript

internal val HbCodeLanguage.hasAnnotations: Boolean
    get() = this != HbCodeLanguage.Json && this != HbCodeLanguage.Shell

internal val HbCodeLanguage.hasIdentifierRoles: Boolean
    get() = this != HbCodeLanguage.Json && this != HbCodeLanguage.Shell

internal val HbCodeLanguage.quotes: String
    get() = when (this) {
        HbCodeLanguage.Json -> "\""
        HbCodeLanguage.JavaScript, HbCodeLanguage.Shell -> "\"'`"
        HbCodeLanguage.Kotlin, HbCodeLanguage.Java, HbCodeLanguage.Python -> "\"'"
    }

internal val HbCodeLanguage.numberSuffixes: String
    get() = when (this) {
        HbCodeLanguage.Kotlin -> "uUlLfF"
        HbCodeLanguage.Java -> "lLfFdD"
        HbCodeLanguage.JavaScript -> "n"
        HbCodeLanguage.Python -> "jJ"
        HbCodeLanguage.Json, HbCodeLanguage.Shell -> ""
    }

private val KotlinKeywords = setOf(
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface", "is",
    "null", "object", "package", "return", "super", "this", "throw", "true", "try", "typealias", "typeof", "val",
    "var", "when", "while", "by", "catch", "constructor", "delegate", "dynamic", "field", "file", "finally", "get",
    "import", "init", "param", "property", "receiver", "set", "setparam", "where", "actual", "abstract", "annotation",
    "companion", "const", "crossinline", "data", "enum", "expect", "external", "final", "infix", "inline", "inner",
    "internal", "lateinit", "noinline", "open", "operator", "out", "override", "private", "protected", "public",
    "reified", "sealed", "suspend", "tailrec", "vararg", "value",
)

private val JavaKeywords = setOf(
    "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue", "default",
    "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if", "implements", "import",
    "instanceof", "int", "interface", "long", "native", "new", "package", "private", "protected", "public", "return",
    "short", "static", "strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try",
    "void", "volatile", "while", "true", "false", "null", "var", "record", "sealed", "permits", "yield", "module",
    "exports", "opens", "requires", "transitive", "uses", "provides", "with",
)

private val JavaScriptKeywords = setOf(
    "as", "async", "await", "break", "case", "catch", "class", "const", "continue", "debugger", "declare", "default",
    "delete", "do", "else", "enum", "export", "extends", "false", "finally", "for", "from", "function", "get", "if",
    "implements", "import", "in", "instanceof", "interface", "keyof", "let", "namespace", "new", "null", "of",
    "package", "private", "protected", "public", "readonly", "return", "set", "static", "super", "switch", "this",
    "throw", "true", "try", "type", "typeof", "undefined", "var", "void", "while", "with", "yield", "abstract",
    "any", "boolean", "never", "number", "object", "string", "symbol", "unknown", "satisfies", "infer",
)

private val JsonKeywords = setOf("true", "false", "null")

private val PythonKeywords = setOf(
    "False", "None", "True", "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del",
    "elif",
    "else", "except", "finally", "for", "from", "global", "if", "import", "in", "is", "lambda", "nonlocal", "not", "or",
    "pass", "raise", "return", "try", "while", "with", "yield", "match", "case",
)

private val ShellKeywords = setOf(
    "if", "then", "else", "elif", "fi", "case", "esac", "for", "while", "until", "do", "done", "in", "function",
    "select", "time", "coproc", "export", "local", "readonly", "declare", "typeset", "return", "break", "continue",
    "source", "alias", "unset", "shift", "trap", "exec", "exit",
)
