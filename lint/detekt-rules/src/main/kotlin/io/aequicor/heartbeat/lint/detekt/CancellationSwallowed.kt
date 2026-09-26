package io.aequicor.heartbeat.lint.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.api.config
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCatchClause
import org.jetbrains.kotlin.psi.KtTryExpression
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

/**
 * `CancellationException` is the only error that may skip logging — but it must never be swallowed,
 * otherwise structured concurrency breaks (cancelled coroutines keep running).
 *
 * Reports:
 * - `catch (e: CancellationException)` that does not rethrow `e`;
 * - in coroutine code: `catch (e: Exception/Throwable/...)` without a preceding
 *   `catch (e: CancellationException) { throw e }`, `if (e is CancellationException) throw e`
 *   or `ensureActive()`;
 * - in coroutine code: `runCatching { }` (it catches `CancellationException`) —
 *   use `suspendRunCatching` from `core:common`.
 *
 * This is a syntax-only complement of detekt's `SuspendFunSwallowedCancellation`, which needs type resolution.
 *
 * <noncompliant>
 * suspend fun load() = try { api.get() } catch (e: Exception) { log.e(e) { "load failed" }; null }
 * </noncompliant>
 *
 * <compliant>
 * suspend fun load() = try {
 *     api.get()
 * } catch (e: CancellationException) {
 *     throw e
 * } catch (e: Exception) {
 *     log.e(e) { "load failed" }
 *     null
 * }
 * </compliant>
 */
class CancellationSwallowed(config: Config) :
    Rule(
        config,
        "CancellationException must be rethrown; generic catches and runCatching in coroutines swallow it.",
    ) {

    /** Catch types that also catch `CancellationException` (it extends `IllegalStateException`). */
    private val cancellationCatchingTypes: List<String> by config(
        listOf("Throwable", "Exception", "RuntimeException", "IllegalStateException"),
    )

    /** Calls whose lambdas run in a coroutine (in addition to `suspend fun` bodies). */
    private val coroutineBuilders: List<String> by config(
        listOf(
            "launch", "async", "withContext", "coroutineScope", "supervisorScope", "withTimeout",
            "withTimeoutOrNull", "runTest", "flow", "channelFlow", "callbackFlow", "produce",
            "LaunchedEffect", "produceState", "collect", "collectLatest", "onEach", "transform",
            "mapLatest", "flatMapLatest", "onStart", "onCompletion", "suspendCoroutine",
            "suspendCancellableCoroutine",
        ),
    )

    /** Calls that restore cancellation after a generic catch. */
    private val cancellationChecks: List<String> by config(listOf("ensureActive", "throwIfCancellation"))

    private val unsafeRunCatching: List<String> by config(listOf("runCatching"))

    override fun visitCatchSection(catchClause: KtCatchClause) {
        super.visitCatchSection(catchClause)
        if (catchClause.typeName() != CANCELLATION) return
        val name = catchClause.catchParameter?.name?.takeUnless { it == "_" }
        if (name == null || catchClause.catchBody?.rethrows(name) != true) {
            report(Finding(Entity.from(catchClause), "CancellationException is swallowed — rethrow it: `throw e`."))
        }
    }

    override fun visitTryExpression(expression: KtTryExpression) {
        super.visitTryExpression(expression)
        if (!isInCoroutineContext(expression, coroutineBuilders)) return
        for (clause in expression.catchClauses) {
            val type = clause.typeName()
            if (type == CANCELLATION) return // handled before any generic catch
            if (type !in cancellationCatchingTypes) continue
            if (!clause.restoresCancellation()) {
                report(
                    Finding(
                        Entity.from(clause),
                        "`catch ($type)` in coroutine code swallows CancellationException — add " +
                            "`catch (e: CancellationException) { throw e }` before it.",
                    ),
                )
            }
            return
        }
    }

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val name = expression.calleeName() ?: return
        if (name in unsafeRunCatching && isInCoroutineContext(expression, coroutineBuilders)) {
            report(
                Finding(
                    Entity.from(expression),
                    "`$name` in coroutine code catches CancellationException — use `suspendRunCatching` (core:common).",
                ),
            )
        }
    }

    private fun KtCatchClause.restoresCancellation(): Boolean {
        val body = catchBody ?: return false
        val name = catchParameter?.name?.takeUnless { it == "_" }
        val rethrows = name != null && body.rethrows(name)
        val checks = body.collectDescendantsOfType<KtCallExpression> { it.calleeName() in cancellationChecks }
        return rethrows || checks.isNotEmpty()
    }

    private fun KtCatchClause.typeName(): String? =
        (catchParameter?.typeReference?.typeElement as? KtUserType)?.referencedName

    private companion object {
        const val CANCELLATION = "CancellationException"
    }
}
