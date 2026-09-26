package io.aequicor.heartbeat.lint.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.api.config
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCatchClause
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

/**
 * No error may be ignored: every place that receives an error must at least log it
 * (`log.w(e)` when recovered, `log.e(e)` when not), or propagate it — rethrow / wrap with cause /
 * hand over to a propagation API (`Result.failure(e)`, `resumeWithException(e)`, ...).
 *
 * Checked handlers: `catch` blocks, Flow `.catch { }`, `Result.onFailure { }`, `Result.getOrElse { }`,
 * `Result.recover { }`, `Result.fold(onFailure = { })`, `CoroutineExceptionHandler { _, e -> }`.
 * `catch (e: CancellationException)` is exempt here — it must be rethrown (see `CancellationSwallowed`).
 *
 * <noncompliant>
 * try { load() } catch (e: IOException) { showError() }
 * flow.catch { emit(Empty) }
 * runCatching { parse(raw) }.onFailure { }
 * </noncompliant>
 *
 * <compliant>
 * try { load() } catch (e: IOException) { log.e(e) { "load failed id=$id" }; showError() }
 * flow.catch { e -> log.w(e) { "fallback to empty" }; emit(Empty) }
 * try { load() } catch (e: IOException) { throw LoadException("load failed", e) }
 * </compliant>
 */
class SwallowedError(config: Config) :
    Rule(
        config,
        "Errors must never be ignored: log them (w/e with the throwable) or propagate them.",
    ) {

    private val loggerReceiverPattern: String by config(DEFAULT_LOGGER_RECEIVER_PATTERN)

    /** Log levels that count as "the error was reported". */
    private val errorLogMethods: List<String> by config(DEFAULT_ERROR_LOG_METHODS)

    /** Require the throwable itself to be passed to the log call (keeps the stack trace). */
    private val requireErrorReference: Boolean by config(true)

    /** Calls that hand the error over to someone else — counted as handling when they receive the error. */
    private val propagationCalls: List<String> by config(
        listOf("failure", "resumeWithException", "completeExceptionally", "close", "cancel", "fail"),
    )

    /** Lambda-based error handlers whose first lambda parameter is the error. */
    private val errorLambdaCalls: List<String> by config(listOf("catch", "onFailure", "getOrElse", "recover"))

    /** Exception types excluded from this rule (handled by `CancellationSwallowed`). */
    private val ignoredExceptionTypes: List<String> by config(listOf("CancellationException"))

    private val logMatcher by lazy { LogCallMatcher(loggerReceiverPattern, DEFAULT_LOG_METHODS) }

    override fun visitCatchSection(catchClause: KtCatchClause) {
        super.visitCatchSection(catchClause)
        val parameter = catchClause.catchParameter ?: return
        val typeName = (parameter.typeReference?.typeElement as? KtUserType)?.referencedName
        if (typeName in ignoredExceptionTypes) return
        val body = catchClause.catchBody ?: return
        checkHandler(catchClause, body, parameter.name?.takeUnless { it == "_" }, "catch ($typeName)")
    }

    override fun visitLambdaExpression(lambdaExpression: KtLambdaExpression) {
        super.visitLambdaExpression(lambdaExpression)
        val call = lambdaExpression.owningCall() ?: return
        val name = call.calleeName() ?: return
        val errorParameterIndex = when {
            name == "CoroutineExceptionHandler" -> 1
            name == "fold" && lambdaExpression.isFailureBranchOfFold(call) -> 0
            name in errorLambdaCalls && call.isErrorHandlerShape(name) -> 0
            else -> return
        }
        val body = lambdaExpression.bodyExpression ?: return
        checkHandler(lambdaExpression, body, lambdaExpression.singleParameterName(errorParameterIndex), "$name { }")
    }

    private fun checkHandler(reportAt: KtElement, body: KtElement, errorName: String?, handler: String) {
        val handled = if (errorName == null) {
            // The error is not even named (`_`), so it can only be "handled" by an unrelated log call.
            !requireErrorReference && logMatcher.containsLogCall(body, errorLogMethods)
        } else {
            body.rethrows(errorName) || body.propagates(errorName) || body.logs(errorName)
        }
        if (handled) return

        val hint = when {
            errorName == null -> "the error is discarded (`_`) — name it and log it"

            logMatcher.containsLogCall(body) && requireErrorReference ->
                "log it at ${errorLogMethods.joinToString("/")} and pass the throwable: log.e($errorName) { \"...\" }"

            else -> "log it (log.w($errorName) if recovered, log.e($errorName) if not) or propagate it"
        }
        report(Finding(Entity.from(reportAt), "Error ignored in `$handler`: $hint."))
    }

    private fun KtElement.logs(errorName: String): Boolean = logMatcher.logCallsIn(this, errorLogMethods).any { call ->
        // Only arguments count: in `log.e { }` the callee itself is named `e`.
        !requireErrorReference ||
            call.valueArguments.any { it.getArgumentExpression()?.referencesName(errorName) == true }
    }

    private fun KtElement.propagates(errorName: String): Boolean =
        collectDescendantsOfType<KtCallExpression> { it.calleeName() in propagationCalls }
            .any { call -> call.valueArguments.any { it.getArgumentExpression()?.referencesName(errorName) == true } }

    /**
     * Only member-style calls without other arguments are error handlers:
     * `flow.catch { }`, `result.getOrElse { }` — but not `list.getOrElse(0) { }`.
     */
    private fun KtCallExpression.isErrorHandlerShape(name: String): Boolean {
        val qualified = parent as? KtQualifiedExpression
        if (qualified == null || qualified.selectorExpression != this) return name == "onFailure"
        return valueArguments.all { it is KtLambdaArgument || it.getArgumentExpression() is KtLambdaExpression }
    }

    private fun KtLambdaExpression.isFailureBranchOfFold(call: KtCallExpression): Boolean {
        val argument = parent as? KtValueArgument ?: return false
        val argumentName = argument.getArgumentName()?.asName?.asString()
        if (argumentName != null) return argumentName == "onFailure"
        // Result.fold({ }, { }) — both branches are lambdas; Iterable.fold(initial) { } is not an error handler.
        val arguments = call.valueArguments
        return arguments.size == 2 &&
            arguments.indexOf(argument) == 1 &&
            arguments[0].getArgumentExpression() is KtLambdaExpression
    }
}
