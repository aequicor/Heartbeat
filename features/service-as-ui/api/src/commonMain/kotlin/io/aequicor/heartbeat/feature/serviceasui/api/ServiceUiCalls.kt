package io.aequicor.heartbeat.feature.serviceasui.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Where surfaces work: the chat [session] when there is one, its [workspace], and the owners [preselected] for a chat
 * that does not exist yet, keyed by provider kind. Providers decide from it which owners are active.
 */
public data class UiCallContext(
    val session: SessionRef? = null,
    val workspace: WorkspaceRef? = null,
    val preselected: Map<String, Set<String>> = emptyMap(),
) {
    override fun toString(): String =
        "UiCallContext(hasSession=${session != null}, hasWorkspace=${workspace != null}, " +
            "preselected=${preselected.mapValues { it.value.size }})"
}

/** What a host binds to one of its places: the [call] context of surfaces there and the [dock] their panels use. */
public data class UiHostContext(val call: UiCallContext = UiCallContext(), val dock: UiDockId? = null)

/** One owner a provider knows: stable [id], slug [name] for the agent, [title] for people and its switch. */
public data class UiOwnerInfo(val id: String, val name: String, val title: String, val isEnabled: Boolean)

/** Owners of one provider. Only an [isComplete] snapshot proves that an owner missing from it was deleted. */
public data class UiOwners(val isComplete: Boolean, val owners: List<UiOwnerInfo> = emptyList())

/** A method an owner publishes for its surfaces. */
public data class UiMethodInfo(val name: String, val description: String)

/**
 * Methods of one owner. [isAvailable] is false where the owner cannot run code (for example on mobile) or while its
 * code is not running; surfaces then show their calls as unavailable. [generation] is an opaque identity of the
 * published implementation, changing on replacement or reactivation even when method names and descriptions
 * stay equal. Surfaces reload automatic sources on its change; ordinary calls and data writes do not change it.
 * Providers without replaceable implementations may leave it null. It grants no authority to invoke methods.
 */
public data class UiMethods(
    val isAvailable: Boolean,
    val methods: List<UiMethodInfo> = emptyList(),
    val generation: String? = null,
)

/**
 * Exact executable source selected for one method and its opaque live [binding]. The binding changes when its
 * activation or callback registration changes, including a restart with identical source. Source and binding
 * are private review inputs, never logged. This snapshot grants no authority to invoke the method.
 */
public data class UiMethodReview(val binding: String, val source: String) {
    init {
        require(binding.isNotBlank()) { "A method review requires a live binding" }
    }

    override fun toString(): String = "UiMethodReview(***)"
}

/**
 * One host-authorized call of [method] with JSON [arguments] in [context]. Surface actions leave [expectedBinding]
 * null. An independently reviewed agent call must pass its reviewed binding; the provider refuses a changed or
 * retired registration immediately before invoking code. A binding never substitutes for context admission.
 */
public data class UiMethodCall(
    val method: String,
    val arguments: JsonObject,
    val context: UiCallContext,
    val expectedBinding: String? = null,
) {
    override fun toString(): String = "UiMethodCall(method=$method, arguments=***, context=$context)"
}

/** Outcome of a method call. Values and messages are user data: they never appear in logs. */
public sealed interface UiMethodResult {
    /** The method returned [value]. */
    public data class Success(val value: JsonElement) : UiMethodResult {
        override fun toString(): String = "Success(value=***)"
    }

    /** The method reported an expected problem; [message] is shown to the user. */
    public data class UserError(val message: String) : UiMethodResult {
        override fun toString(): String = "UserError(message=***)"
    }

    /** The call produced no result, for [reason]. */
    public data class Failure(val reason: UiMethodFailure) : UiMethodResult
}

/** Why a method call produced no result. */
public enum class UiMethodFailure {
    /** The provider cannot run here or now, or the reviewed callback binding is no longer current. */
    Unavailable,

    /** The owner does not publish this method. */
    NotFound,

    /** The owner is disabled, deleted or not active in the call context. */
    Inactive,

    /** The method ran longer than allowed. */
    TimedOut,

    /** The method failed; details are in the provider's log. */
    Failed,

    /** The arguments or the result exceed the limits. */
    TooLarge,
}
