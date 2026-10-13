package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import io.aequicor.heartbeat.feature.harness.api.HarnessState
import io.aequicor.heartbeat.feature.harness.api.ItemName
import io.aequicor.heartbeat.feature.harness.api.script.ScriptUiCall
import io.aequicor.heartbeat.feature.harness.api.script.ScriptUiResult
import io.aequicor.heartbeat.feature.harness.impl.domain.content.HarnessActiveSnapshot
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.JsonElement

/** A method a harness publishes for its interfaces. */
internal data class HarnessUiMethod(val name: ItemName, val description: String) {
    override fun toString(): String = "HarnessUiMethod(name=${name.value})"
}

/** Outcome of [HarnessUiDispatch.call]; values and messages are user data and never logged. */
internal sealed interface HarnessUiOutcome {
    /** The method returned [value]. */
    data class Value(val value: JsonElement) : HarnessUiOutcome {
        override fun toString(): String = "HarnessUiOutcome.Value(***)"
    }

    /** The method reported an expected problem for the user. */
    data class UserError(val message: String) : HarnessUiOutcome {
        override fun toString(): String = "HarnessUiOutcome.UserError(***)"
    }

    /** No published method with this name. */
    data object NotFound : HarnessUiOutcome

    /** The harness is not active in the caller's chat. */
    data object Inactive : HarnessUiOutcome

    /** The activation is gone or its code is not running. */
    data object Unavailable : HarnessUiOutcome

    /** The method ran longer than its budget. */
    data object TimedOut : HarnessUiOutcome

    /** The method threw; it counts towards the item's failures. */
    data object Failed : HarnessUiOutcome

    /** The value exceeds [HarnessLimits.UI_RESULT_BYTES]. */
    data object TooLarge : HarnessUiOutcome
}

/**
 * Runs UI methods for user actions, outside agent turns. A call in a chat requires the harness to be active there
 * (see [connectedAdmission]); a call without a chat (a new chat or settings) requires only a running activation.
 * Each call is admitted again immediately before author code runs and is cancelled together with its caller.
 */
internal class HarnessUiDispatch(private val runtime: HarnessRuntime, private val sessions: HarnessSessionAdmission) {
    private val log = Log.tag("HarnessUiMethods")

    /** Methods the running activations of [harness] publish now. */
    @HighFrequency
    suspend fun methods(harness: HarnessId): List<HarnessUiMethod> = bindings(harness).map { (_, method) ->
        HarnessUiMethod(method.name, method.description)
    }

    /** Runs the method [name] of [harness] for a user action. */
    suspend fun call(harness: HarnessId, name: String, call: ScriptUiCall): HarnessUiOutcome {
        log.v { "dispatch harness ui method" }
        val session = call.session
        val binding = bindings(harness).firstOrNull { (_, method) -> method.name.value == name }
        val isAdmitted = { session == null || sessions.allows(harness, session) }
        return when {
            binding == null -> HarnessUiOutcome.NotFound
            !isAdmitted() -> HarnessUiOutcome.Inactive
            else -> invoke(binding.first, binding.second, call, isAdmitted)
        }
    }

    private suspend fun invoke(
        instance: HarnessInstance,
        method: HarnessUiMethodRegistration,
        call: ScriptUiCall,
        isAdmitted: () -> Boolean,
    ): HarnessUiOutcome {
        val lifetime = currentCoroutineContext()[Job]
        val options = HarnessInvocationOptions(method.callback.origin, lifetime)
        val result = runtime.invoke(instance, HarnessInvocationBudget.UiMethod, options) {
            if (isAdmitted()) method.callback.acquire()?.invoke(call) else null
        }
        return when (result) {
            null, is HarnessInvocationResult.Cancelled -> HarnessUiOutcome.Unavailable

            HarnessInvocationResult.TimedOut -> HarnessUiOutcome.TimedOut

            is HarnessInvocationResult.Completed -> when (val attempt = result.value) {
                is HarnessAttempt.Success -> attempt.value?.outcome() ?: HarnessUiOutcome.Unavailable
                is HarnessAttempt.Failure -> HarnessUiOutcome.Failed
            }
        }
    }

    @HighFrequency
    private suspend fun bindings(harness: HarnessId): List<Pair<HarnessInstance, HarnessUiMethodRegistration>> =
        runtime.published().filter { it.request.harness.id == harness }.flatMap { instance ->
            val context = instance.runtimeContext as? HarnessScriptContext
            context?.registrations?.methods().orEmpty().map { instance to it }
        }
}

private fun ScriptUiResult.outcome(): HarnessUiOutcome = when (this) {
    is ScriptUiResult.Value ->
        if (value.toString().encodeToByteArray().size > HarnessLimits.UI_RESULT_BYTES) {
            HarnessUiOutcome.TooLarge
        } else {
            HarnessUiOutcome.Value(value)
        }

    is ScriptUiResult.Error -> HarnessUiOutcome.UserError(message.take(HarnessLimits.UI_ERROR_CHARS))
}

/**
 * Admission of UI calls: the trusted host passes the chat it shows, never a script or the model, so a committed
 * connection of the enabled harness to that chat is the authority. Unlike tool calls, no facade callback has to
 * prove the chat's route first: buttons work in a chat restored after a restart before its first turn.
 */
internal fun connectedAdmission(library: () -> HarnessState): HarnessSessionAdmission =
    HarnessSessionAdmission { harness, session ->
        val state = library()
        state !is HarnessState.Failed &&
            HarnessActiveSnapshot(isEnabled = true, library = state).select(session)?.any { it.id == harness } == true
    }
