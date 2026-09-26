package io.aequicor.heartbeat.lint.detekt

import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

/** Default log-method names of the `core:logging` facade (`Log.tag(..).e(error) { .. }`) and common loggers. */
internal val DEFAULT_LOG_METHODS = listOf("v", "d", "i", "w", "e", "wtf", "verbose", "debug", "info", "warn", "error")

/** Default log-method names that are acceptable for reporting a caught error. */
internal val DEFAULT_ERROR_LOG_METHODS = listOf("w", "e", "wtf", "warn", "error")

/** Default regex for the leftmost receiver of a log call: `log`, `logger`, `Log`, `chatLog`, `httpLogger`. */
internal const val DEFAULT_LOGGER_RECEIVER_PATTERN = "^(log|logger|Log|[a-z][A-Za-z0-9]*(Log|Logger))$"

/**
 * Recognises calls to the logging facade by shape: `<logger>.<method>(...)`,
 * where the leftmost receiver name matches [receiverPattern] and the method name is in [methods].
 */
internal class LogCallMatcher(receiverPattern: String, private val methods: Collection<String>) {

    private val receiverRegex = Regex(receiverPattern)

    fun isLogCall(call: KtCallExpression, allowedMethods: Collection<String> = methods): Boolean {
        val name = call.calleeName() ?: return false
        if (name !in allowedMethods) return false
        val qualified = call.parent as? KtQualifiedExpression ?: return false
        if (qualified.selectorExpression != call) return false
        val receiver = qualified.receiverExpression.leftmostName() ?: return false
        return receiverRegex.matches(receiver)
    }

    /** All log calls inside [element] (including nested lambdas). */
    fun logCallsIn(element: KtElement, allowedMethods: Collection<String> = methods): List<KtCallExpression> =
        element.collectDescendantsOfType<KtCallExpression> { isLogCall(it, allowedMethods) }

    fun containsLogCall(element: KtElement, allowedMethods: Collection<String> = methods): Boolean =
        logCallsIn(element, allowedMethods).isNotEmpty()
}
