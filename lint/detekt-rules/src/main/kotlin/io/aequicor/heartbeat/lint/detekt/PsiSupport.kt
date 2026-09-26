package io.aequicor.heartbeat.lint.detekt

import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtAnnotated
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtSafeQualifiedExpression
import org.jetbrains.kotlin.psi.KtThisExpression
import org.jetbrains.kotlin.psi.KtThrowExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType

internal fun KtCallExpression.calleeName(): String? =
    (calleeExpression as? KtNameReferenceExpression)?.getReferencedName()

/** `a.b.c(...)` -> `a`; `this.log.e()` -> `log`; `Log.tag("X").e()` -> `Log`. */
internal fun KtExpression.leftmostName(): String? = when (this) {
    is KtNameReferenceExpression -> getReferencedName()

    is KtCallExpression -> calleeName()

    is KtQualifiedExpression -> {
        val receiver = receiverExpression
        if (receiver is KtThisExpression) selectorExpression?.leftmostName() else receiver.leftmostName()
    }

    else -> null
}

/** Whether any name reference inside [element] (or [element] itself) resolves by name to [name]. */
internal fun KtElement.referencesName(name: String): Boolean =
    (this is KtNameReferenceExpression && getReferencedName() == name) ||
        collectDescendantsOfType<KtNameReferenceExpression> { it.getReferencedName() == name }.isNotEmpty()

/** Whether [element] contains a `throw` whose expression references [name] (rethrow or wrap with cause). */
internal fun KtElement.rethrows(name: String): Boolean =
    collectDescendantsOfType<KtThrowExpression>().any { it.thrownExpression?.referencesName(name) == true }

/** The call a lambda is passed to: `foo { }`, `foo() { }`, `foo(block = { })`. */
internal fun KtLambdaExpression.owningCall(): KtCallExpression? {
    val argument = parent as? KtValueArgument ?: (parent as? KtLambdaArgument) ?: return null
    return argument.getStrictParentOfType<KtCallExpression>()
}

/** Name of the lambda's single parameter (`it` when implicit), or `null` for `_`/destructuring. */
internal fun KtLambdaExpression.singleParameterName(index: Int = 0): String? {
    val parameters = valueParameters
    if (parameters.isEmpty()) return if (index == 0) "it" else null
    val parameter = parameters.getOrNull(index) ?: return null
    if (parameter.destructuringDeclaration != null) return null
    val name = parameter.name ?: return null
    return name.takeUnless { it == "_" }
}

internal fun KtAnnotated.hasAnnotation(vararg names: String): Boolean =
    annotationEntries.any { it.shortName?.asString() in names }

internal fun KtFile.packageName(): String = packageFqName.asString()

internal fun String.isInPackage(prefix: String): Boolean = this == prefix || startsWith("$prefix.")

/**
 * Whether [element] executes in a coroutine: inside a `suspend fun`, or inside a lambda passed to one of
 * [coroutineBuilders]. Lambdas that are not builders are transparent (most of them are inline).
 */
internal fun isInCoroutineContext(element: KtElement, coroutineBuilders: Collection<String>): Boolean =
    generateSequence(element.parent) { it.parent }
        .takeWhile { it !is KtFile && it !is KtClassOrObject }
        .firstNotNullOfOrNull { parent ->
            when {
                parent is KtNamedFunction -> parent.hasModifier(KtTokens.SUSPEND_KEYWORD)
                parent is KtLambdaExpression && parent.owningCall()?.calleeName() in coroutineBuilders -> true
                else -> null
            }
        } ?: false

/** Selector call names of a call chain that starts at [start]: `start.a().b { }.c` -> [a, b, c]. */
internal fun chainAfter(start: KtExpression): Pair<KtExpression, List<String>> {
    var top: KtExpression = start
    val selectors = mutableListOf<String>()
    while (true) {
        val parent = top.parent
        if ((parent is KtDotQualifiedExpression || parent is KtSafeQualifiedExpression) &&
            (parent as KtQualifiedExpression).receiverExpression == top
        ) {
            when (val selector = parent.selectorExpression) {
                is KtCallExpression -> selector.calleeName()?.let(selectors::add)
                is KtNameReferenceExpression -> selectors.add(selector.getReferencedName())
                else -> Unit
            }
            top = parent
        } else {
            return top to selectors
        }
    }
}
