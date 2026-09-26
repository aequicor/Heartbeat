package io.aequicor.heartbeat.lint.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.api.config
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtQualifiedExpression

/**
 * A `runCatching { }` result must have its failure handled in the same chain. Discarding the `Result`
 * or collapsing it with `getOrNull()`/`getOrDefault()` silently drops the error.
 *
 * Handling operators: `onFailure`, `getOrElse`, `fold`, `recover`, `recoverCatching`, `exceptionOrNull`,
 * `getOrThrow`. A `Result` that is returned/assigned is not reported — the receiver must handle it.
 *
 * <noncompliant>
 * runCatching { cache.clear() }
 * val user = runCatching { parse(raw) }.getOrNull()
 * </noncompliant>
 *
 * <compliant>
 * runCatching { cache.clear() }.onFailure { log.w(it) { "cache clear failed" } }
 * val user = runCatching { parse(raw) }.onFailure { log.e(it) { "bad payload" } }.getOrNull()
 * </compliant>
 */
class UnhandledResultFailure(config: Config) :
    Rule(
        config,
        "The failure of runCatching { } must be handled (onFailure/getOrElse/fold/recover) in the same chain.",
    ) {

    private val resultProducers: List<String> by config(listOf("runCatching", "suspendRunCatching", "mapCatching"))

    private val failureHandlers: List<String> by config(
        listOf("onFailure", "getOrElse", "fold", "recover", "recoverCatching", "exceptionOrNull", "getOrThrow"),
    )

    /** Terminal operators that throw the error away. */
    private val failureDiscarders: List<String> by config(listOf("getOrNull", "getOrDefault", "isSuccess"))

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val name = expression.calleeName() ?: return
        if (name !in resultProducers) return
        // `x.runCatching { }` / `result.mapCatching { }` — the chain starts at the qualified expression.
        val parent = expression.parent
        val start: KtExpression =
            if (parent is KtQualifiedExpression && parent.selectorExpression == expression) parent else expression
        val (top, selectors) = chainAfter(start)
        // Only the last producer in a chain is responsible: runCatching { }.mapCatching { }.onFailure { }.
        if (selectors.any { it in resultProducers }) return
        if (selectors.any { it in failureHandlers }) return

        val discarder = selectors.firstOrNull { it in failureDiscarders }
        when {
            discarder != null -> report(
                Finding(
                    Entity.from(expression),
                    "`$name { }.$discarder()` drops the error — add `.onFailure { log.w(it) { \"...\" } }` first.",
                ),
            )

            top.isDiscardedStatement() -> report(
                Finding(
                    Entity.from(expression),
                    "Result of `$name { }` is ignored — handle it: `.onFailure { log.e(it) { \"...\" } }`.",
                ),
            )
        }
    }

    private fun KtExpression.isDiscardedStatement(): Boolean {
        val block = parent as? KtBlockExpression ?: return false
        // The last statement of a lambda is its value (implicit return).
        val isLambdaValue = block.parent is KtFunctionLiteral && block.statements.lastOrNull() == this
        return !isLambdaValue
    }
}
