package io.aequicor.heartbeat.lint.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.api.config
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtAnonymousInitializer
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType

/**
 * Every state change must be logged. Stores (`heartbeatStore { }`) and state machines log automatically
 * via `core:mvi` / `core:state-machine`; hand-written state holders (`MutableStateFlow`, `MutableSharedFlow`,
 * `mutableStateOf` in classes) must log in the function that mutates them.
 *
 * Mutations: `holder.value = / += ...`, `holder.update { }`, `getAndUpdate`, `updateAndGet`,
 * `compareAndSet`, `emit`, `tryEmit`, and assignments to `var x by mutableStateOf(...)`.
 * `@Composable` functions are ignored (local UI state is not business state).
 *
 * <noncompliant>
 * class SessionHolder {
 *     private val _session = MutableStateFlow<Session?>(null)
 *     fun signOut() { _session.value = null }
 * }
 * </noncompliant>
 *
 * <compliant>
 * class SessionHolder {
 *     private val _session = MutableStateFlow<Session?>(null)
 *     fun signOut() {
 *         log.i { "session: signed out" }
 *         _session.value = null
 *     }
 * }
 * </compliant>
 */
class StateChangeNotLogged(config: Config) :
    Rule(
        config,
        "Mutations of state holders (MutableStateFlow/SharedFlow/mutableStateOf) must be logged.",
    ) {

    private val loggerReceiverPattern: String by config(DEFAULT_LOGGER_RECEIVER_PATTERN)

    private val stateHolderFactories: List<String> by config(
        listOf("MutableStateFlow", "MutableSharedFlow", "mutableStateOf", "mutableStateListOf", "mutableStateMapOf"),
    )

    private val mutatingCalls: List<String> by config(
        listOf("update", "getAndUpdate", "updateAndGet", "compareAndSet", "emit", "tryEmit"),
    )

    /** Packages whose infrastructure logs state changes itself (store plugin, machine listener). */
    private val allowedPackages: List<String> by config(
        listOf("io.aequicor.heartbeat.core.mvi", "io.aequicor.heartbeat.core.statemachine"),
    )

    private val logMatcher by lazy { LogCallMatcher(loggerReceiverPattern, DEFAULT_LOG_METHODS) }

    override fun visitKtFile(file: KtFile) {
        if (allowedPackages.none { file.packageName().isInPackage(it) }) super.visitKtFile(file)
    }

    override fun visitClassOrObject(classOrObject: KtClassOrObject) {
        super.visitClassOrObject(classOrObject)
        val properties = classOrObject.body?.properties.orEmpty()
        val holders = properties.filter { it.initializer?.callsAny(stateHolderFactories) == true }
            .mapNotNull(KtProperty::getName).toSet()
        val delegated = properties.filter { it.delegateExpression?.callsAny(stateHolderFactories) == true }
            .mapNotNull(KtProperty::getName).toSet()
        if (holders.isEmpty() && delegated.isEmpty()) return

        val mutations = classOrObject.collectDescendantsOfType<KtExpression> { expression ->
            expression.getStrictParentOfType<KtClassOrObject>() == classOrObject &&
                expression.mutatedHolder(holders, delegated) != null
        }
        mutations.groupBy { it.owningDeclaration() }.forEach { (owner, ownerMutations) ->
            if (owner == null || owner.isComposable() || logMatcher.containsLogCall(owner)) return@forEach
            val first = ownerMutations.first()
            val holder = first.mutatedHolder(holders, delegated) ?: return@forEach
            report(
                Finding(
                    Entity.from(first),
                    "State `$holder` changes without a log in `${owner.name ?: "init"}` — " +
                        "add log.i { \"$holder: old -> new\" } (or log.d for high-frequency updates).",
                ),
            )
        }
    }

    /** Returns the holder name if this expression mutates one. */
    private fun KtExpression.mutatedHolder(holders: Set<String>, delegated: Set<String>): String? = when (this) {
        is KtBinaryExpression -> {
            val left = left
            when {
                operationToken !in ASSIGNMENTS -> null

                left is KtNameReferenceExpression && left.getReferencedName() in delegated -> left.getReferencedName()

                left is KtQualifiedExpression && left.selectorExpression?.text == "value" ->
                    left.receiverExpression.holderName(holders)

                else -> null
            }
        }

        is KtCallExpression -> {
            val qualified = parent as? KtQualifiedExpression
            if (calleeName() in mutatingCalls && qualified?.selectorExpression == this) {
                qualified.receiverExpression.holderName(holders)
            } else {
                null
            }
        }

        else -> null
    }

    /** `_state` or `this._state` -> `_state` when it is a known holder. */
    private fun KtExpression.holderName(holders: Set<String>): String? {
        val name = when (this) {
            is KtNameReferenceExpression -> getReferencedName()
            is KtQualifiedExpression -> if (receiverExpression.text == "this") selectorExpression?.text else null
            else -> null
        }
        return name?.takeIf { it in holders }
    }

    private fun KtElement.owningDeclaration(): KtDeclaration? = generateSequence(parent) { it.parent }
        .takeWhile { it !is KtClassOrObject }
        .filterIsInstance<KtDeclaration>()
        .firstOrNull { it.isMutationOwner() }

    /** Functions, accessors, init blocks and class-level property initializers (not local variables). */
    private fun KtDeclaration.isMutationOwner(): Boolean = this is KtNamedFunction ||
        this is KtPropertyAccessor ||
        this is KtAnonymousInitializer ||
        (this is KtProperty && getStrictParentOfType<KtNamedFunction>() == null)

    private fun KtDeclaration.isComposable(): Boolean = this is KtNamedFunction && hasAnnotation("Composable")

    private fun KtExpression.callsAny(names: Collection<String>): Boolean =
        (this is KtCallExpression && calleeName() in names) ||
            collectDescendantsOfType<KtCallExpression> { it.calleeName() in names }.isNotEmpty()

    private companion object {
        val ASSIGNMENTS = setOf(
            KtTokens.EQ,
            KtTokens.PLUSEQ,
            KtTokens.MINUSEQ,
            KtTokens.MULTEQ,
            KtTokens.DIVEQ,
            KtTokens.PERCEQ,
        )
    }
}
