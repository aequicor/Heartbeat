package io.aequicor.heartbeat.lint.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.api.config
import org.jetbrains.kotlin.descriptors.annotations.AnnotationUseSiteTarget
import org.jetbrains.kotlin.psi.KtAnnotated
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPropertyAccessor

/**
 * Keeps routine high-frequency diagnostics out of the default DEBUG/INFO output.
 *
 * Checks calls nested in functions/accessors marked with core:logging's @HighFrequency, including
 * lambdas and local functions. Also checks lambdas passed to update/getAndUpdate/updateAndGet:
 * these transforms may retry and must not produce routine DEBUG/INFO output per attempt.
 * VERBOSE and warnings/errors remain allowed; an error must not be downgraded to bypass this rule.
 *
 * This is a syntax-only rule using the shared logger receiver matcher and short annotation/call names.
 * It does not infer frequency from message text, follow helper calls or prove that a guard limits rate.
 * Mark extracted hot-path helpers explicitly; put bounded operation summaries outside the handler.
 *
 * <noncompliant>
 * @HighFrequency
 * fun publish() { log.d { "History revision advanced" } }
 * </noncompliant>
 *
 * <compliant>
 * @HighFrequency
 * fun publish() { log.v { "History revision advanced" } }
 * </compliant>
 */
class HighFrequencyLog(config: Config) :
    Rule(config, "High-frequency diagnostics must use VERBOSE, not DEBUG/INFO.") {

    private val loggerReceiverPattern: String by config(DEFAULT_LOGGER_RECEIVER_PATTERN)
    private val highFrequencyAnnotations: List<String> by config(listOf("HighFrequency"))
    private val repeatedCalls: List<String> by config(listOf("update", "getAndUpdate", "updateAndGet"))
    private val logMatcher by lazy {
        LogCallMatcher(loggerReceiverPattern, listOf("d", "i", "debug", "info"))
    }

    private fun KtAnnotated.isHighFrequency(): Boolean =
        annotationEntries.any { it.shortName?.asString() in highFrequencyAnnotations }

    private fun KtPropertyAccessor.hasHighFrequencyUseSite(): Boolean {
        val target = if (isGetter) AnnotationUseSiteTarget.PROPERTY_GETTER else AnnotationUseSiteTarget.PROPERTY_SETTER
        return property.annotationEntries.any {
            it.shortName?.asString() in highFrequencyAnnotations &&
                it.useSiteTarget?.getAnnotationUseSiteTarget() == target
        }
    }

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (!logMatcher.isLogCall(expression)) return
        val isFrequent = generateSequence(expression.parent) { it.parent }.any { parent ->
            when (parent) {
                is KtNamedFunction -> parent.isHighFrequency()
                is KtPropertyAccessor -> parent.isHighFrequency() || parent.hasHighFrequencyUseSite()
                is KtLambdaExpression -> parent.owningCall()?.calleeName() in repeatedCalls
                else -> false
            }
        }
        if (isFrequent) {
            report(
                Finding(
                    Entity.from(expression),
                    "Routine high-frequency log — use log.v; emit DEBUG/INFO summaries at an operation boundary. " +
                        "Keep real failures at WARN/ERROR with their throwable.",
                ),
            )
        }
    }
}
