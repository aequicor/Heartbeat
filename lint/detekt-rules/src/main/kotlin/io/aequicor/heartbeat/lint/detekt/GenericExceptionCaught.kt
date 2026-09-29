package io.aequicor.heartbeat.lint.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.api.config
import org.jetbrains.kotlin.psi.KtCatchClause
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtIsExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtTryExpression
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

/**
 * Replacement for detekt's `TooGenericExceptionCaught` that knows the Heartbeat cancellation pattern:
 * catching too generic exceptions hides bugs, but `catch (e: Exception)` is allowed once
 * `CancellationException` has been rethrown before it — by a preceding
 * `catch (e: CancellationException) { throw e }` clause or by `if (e is CancellationException) throw e`
 * inside the same catch.
 *
 * <noncompliant>
 * try { load() } catch (e: Exception) { log.e(e) { "load failed" } }
 * try { load() } catch (e: Throwable) { log.e(e) { "load failed" } }
 * </noncompliant>
 *
 * <compliant>
 * try {
 *     load()
 * } catch (e: CancellationException) {
 *     throw e
 * } catch (e: Exception) {
 *     log.e(e) { "load failed" }
 * }
 * </compliant>
 */
class GenericExceptionCaught(config: Config) :
    Rule(
        config,
        "Too generic exceptions must not be caught; Exception is allowed only after rethrowing CancellationException.",
    ) {

    /** Exception types that are too generic to catch. */
    private val exceptionNames: List<String> by config(
        listOf(
            "ArrayIndexOutOfBoundsException",
            "Error",
            "Exception",
            "IllegalMonitorStateException",
            "IndexOutOfBoundsException",
            "NullPointerException",
            "RuntimeException",
            "Throwable",
        ),
    )

    /** Generic types that become acceptable once `CancellationException` has been rethrown. */
    private val allowedAfterCancellationRethrow: List<String> by config(listOf("Exception"))

    override fun visitTryExpression(expression: KtTryExpression) {
        super.visitTryExpression(expression)
        var isCancellationRethrown = false
        for (clause in expression.catchClauses) {
            val type = clause.typeName() ?: continue
            when {
                type == CANCELLATION -> isCancellationRethrown = clause.rethrowsOwnError()

                type in exceptionNames -> {
                    val isAllowed = type in allowedAfterCancellationRethrow &&
                        (isCancellationRethrown || clause.rethrowsCancellationFirst())
                    if (!isAllowed) report(Finding(Entity.from(clause), clause.message(type)))
                }
            }
        }
    }

    private fun KtCatchClause.message(type: String): String = if (type in allowedAfterCancellationRethrow) {
        "`catch ($type)` is too generic — catch specific exceptions, or rethrow CancellationException first: " +
            "`catch (e: CancellationException) { throw e }`."
    } else {
        "`catch ($type)` is too generic — catch specific exceptions (at most `Exception` after rethrowing " +
            "CancellationException)."
    }

    private fun KtCatchClause.rethrowsOwnError(): Boolean {
        val name = catchParameter?.name?.takeUnless { it == "_" } ?: return false
        return catchBody?.rethrows(name) == true
    }

    /** `if (e is CancellationException) throw e` inside this catch. */
    private fun KtCatchClause.rethrowsCancellationFirst(): Boolean {
        val name = catchParameter?.name?.takeUnless { it == "_" } ?: return false
        val body = catchBody ?: return false
        return body.collectDescendantsOfType<KtIfExpression>().any { check ->
            val condition = check.condition as? KtIsExpression
            val subject = condition?.leftHandSide as? KtNameReferenceExpression
            val checkedType = (condition?.typeReference?.typeElement as? KtUserType)?.referencedName
            subject?.getReferencedName() == name &&
                checkedType == CANCELLATION &&
                !condition.isNegated &&
                check.then?.rethrows(name) == true
        }
    }

    private fun KtCatchClause.typeName(): String? =
        (catchParameter?.typeReference?.typeElement as? KtUserType)?.referencedName

    private companion object {
        const val CANCELLATION = "CancellationException"
    }
}
