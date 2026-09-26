package io.aequicor.heartbeat.lint.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.api.config
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType

/**
 * Secrets are never logged: API keys, tokens, passwords, `Authorization` headers, cookies.
 * Reports log calls whose message/arguments reference such values, unless wrapped in `redact(...)`
 * or reduced to a safe property (`apiKey.length`, `token.isBlank()`).
 *
 * <noncompliant>
 * log.d { "init provider key=$apiKey" }
 * </noncompliant>
 *
 * <compliant>
 * log.d { "init provider key=${Log.redact(apiKey)} len=${apiKey.length}" }
 * </compliant>
 */
class SensitiveDataLogged(config: Config) :
    Rule(
        config,
        "Secrets (API keys, tokens, passwords, auth headers, cookies) must never be logged.",
    ) {

    private val loggerReceiverPattern: String by config(DEFAULT_LOGGER_RECEIVER_PATTERN)

    /** Matched against referenced identifiers, case-insensitive. */
    private val sensitiveNamePattern: String by config(
        "(?i)^(.*(password|passwd|passphrase|secret|apikey|api_key|accesstoken|refreshtoken|authtoken|" +
            "bearer|idtoken|privatekey|credential|credentials|cookie|cookies|authorization|sessionid)|token)$",
    )

    /** Calls that make a secret safe to log. */
    private val redactionCalls: List<String> by config(listOf("redact", "mask", "sha256", "hash"))

    /** Selectors that reduce a secret to a non-sensitive value. */
    private val safeSelectors: List<String> by config(
        listOf("length", "size", "isEmpty", "isNotEmpty", "isBlank", "isNotBlank", "isNullOrEmpty", "isNullOrBlank"),
    )

    private val logMatcher by lazy { LogCallMatcher(loggerReceiverPattern, DEFAULT_LOG_METHODS) }
    private val sensitiveRegex by lazy { Regex(sensitiveNamePattern) }

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (!logMatcher.isLogCall(expression)) return
        val leaked = expression.collectDescendantsOfType<KtNameReferenceExpression> { reference ->
            sensitiveRegex.matches(reference.getReferencedName()) &&
                !reference.isReducedToSafeValue() &&
                !reference.isRedacted(expression)
        }
        leaked.distinctBy { it.getReferencedName() }.forEach { reference ->
            report(
                Finding(
                    Entity.from(reference),
                    "`${reference.getReferencedName()}` looks like a secret — never log it; use Log.redact(...) " +
                        "or log only its length.",
                ),
            )
        }
    }

    /** `apiKey.length` / `config.apiKey.isBlank()`. */
    private fun KtNameReferenceExpression.isReducedToSafeValue(): Boolean {
        val parent = parent
        // `config.apiKey` — the value is the whole qualified expression.
        val value = if (parent is KtQualifiedExpression && parent.selectorExpression == this) parent else this
        val holder = value.parent as? KtQualifiedExpression ?: return false
        if (holder.receiverExpression != value) return false
        val selector = when (val s = holder.selectorExpression) {
            is KtCallExpression -> s.calleeName()
            is KtNameReferenceExpression -> s.getReferencedName()
            else -> null
        }
        return selector in safeSelectors
    }

    private fun KtNameReferenceExpression.isRedacted(logCall: KtCallExpression): Boolean {
        var call = getStrictParentOfType<KtCallExpression>()
        while (call != null && call != logCall) {
            if (call.calleeName() in redactionCalls) return true
            call = call.getStrictParentOfType<KtCallExpression>()
        }
        return false
    }
}
