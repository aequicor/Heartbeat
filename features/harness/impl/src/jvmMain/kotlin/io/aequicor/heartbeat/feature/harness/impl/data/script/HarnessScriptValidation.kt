package io.aequicor.heartbeat.feature.harness.impl.data.script

import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCodeDiagnostic
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessDiagnosticSeverity
import org.jetbrains.kotlin.lexer.KotlinLexer
import org.jetbrains.kotlin.lexer.KtTokens

/** Lexical restrictions concern executable tokens, never text in comments or ordinary string literals. */
internal fun validateHarnessSource(source: String): List<HarnessCodeDiagnostic> {
    val lexer = KotlinLexer().apply { start(source) }
    var depth = 0
    val failures = mutableListOf<HarnessCodeDiagnostic>()
    while (lexer.tokenType != null) {
        val token = lexer.tokenType
        val violation = lexer.violation(depth)
        if (violation != null) {
            val offset = lexer.tokenStart
            val line = source.take(offset).count { it == '\n' } + 1
            val column = offset - source.lastIndexOf('\n', (offset - 1).coerceAtLeast(0))
            failures += HarnessCodeDiagnostic(HarnessDiagnosticSeverity.Error, violation, line, column)
        }
        if (token == KtTokens.LBRACE) depth++
        if (token == KtTokens.RBRACE) depth--
        lexer.advance()
    }
    return failures.take(MAX_HARNESS_DIAGNOSTICS)
}

internal const val MAX_HARNESS_DIAGNOSTICS = 20
internal const val MAX_HARNESS_DIAGNOSTIC_CHARS = 2048

private fun KotlinLexer.violation(depth: Int): String? {
    val text = tokenText.removeSurrounding("`")
    val isSuspend = tokenType == KtTokens.SUSPEND_KEYWORD ||
        (tokenType == KtTokens.IDENTIFIER && tokenText == "suspend")
    return when {
        tokenType == KtTokens.IDENTIFIER && text == "println" -> "println is unavailable; use harness services"

        tokenType == KtTokens.IDENTIFIER && text == "Serializable" ->
            "Serialization declarations are unavailable in scripts"

        isSuspend && depth == 0 -> "Top-level suspend is unavailable; use script.scope.launch"

        else -> null
    }
}
