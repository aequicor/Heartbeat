package io.aequicor.heartbeat.feature.serviceasui.api

import io.aequicor.heartbeat.core.statemachine.StateBuilder
import kotlinx.serialization.json.JsonObject

/** The canonical library app of a host; a card is only its optional durable conversation receipt. */
public data class UiAppTarget(
    val surface: UiSurfaceId,
    val args: JsonObject = JsonObject(emptyMap()),
    val cardId: String? = null,
) {
    override fun toString(): String = "UiAppTarget(surface=${surface.value}, cardId=${cardId ?: "none"})"
}

/**
 * One app selected for a bound host. The surface supplies title, description and owner from the current library.
 * [context] is its authority, including preselected owners before a chat exists. Modal visibility is transient;
 * [sequence] rejects a close queued before a newer opening. Arguments never appear in logs.
 */
public data class UiApp(
    val surface: UiSurfaceId,
    val context: UiHostContext,
    val args: JsonObject = JsonObject(emptyMap()),
    val cardId: String? = null,
    val isExpanded: Boolean = false,
    val sequence: Long = 0,
) {
    override fun toString(): String = "UiApp(surface=${surface.value}, isExpanded=$isExpanded, sequence=$sequence)"
}

private typealias AppStateBuilder =
    StateBuilder<ServiceUiState, ServiceUiState.Ready, ServiceUiIntent, ServiceUiEffect, ServiceUiOutput>

internal fun AppStateBuilder.appTransitions() {
    on<ServiceUiIntent.Internal.ResolveApp>(guard = { state.canResolveApp(intent) }) {
        stay { state.resolvedApp(intent) }
    }
    on<ServiceUiIntent.Public.OpenApp>(guard = {
        state.hosts[intent.host] == intent.expectedContext && state.apps[intent.host]?.let {
            it.surface == intent.surface && it.context == intent.expectedContext &&
                state.library[it.surface]?.isEnabled == true
        } == true
    }) {
        stay {
            val sequence = state.appSequence + 1
            val app = state.apps.getValue(intent.host).copy(isExpanded = true, sequence = sequence)
            state.copy(apps = state.apps + (intent.host to app), appSequence = sequence)
        }
    }
    on<ServiceUiIntent.Public.CloseApp>(guard = {
        state.apps[intent.host]?.let { it.isExpanded && it.sequence == intent.sequence } == true
    }) {
        stay {
            val app = state.apps.getValue(intent.host).copy(isExpanded = false)
            state.copy(apps = state.apps + (intent.host to app))
        }
    }
}

private fun ServiceUiState.Ready.canResolveApp(intent: ServiceUiIntent.Internal.ResolveApp): Boolean =
    hosts[intent.host] == intent.expectedContext && library.revision == intent.expectedLibraryRevision &&
        (
            intent.target == null || (
                library[intent.target.surface]?.isEnabled == true &&
                    intent.target.args.toString().encodeToByteArray().size <= ServiceUiLimits.ARGS_BYTES
            )
        )

private fun ServiceUiState.Ready.resolvedApp(intent: ServiceUiIntent.Internal.ResolveApp): ServiceUiState.Ready {
    val target = intent.target ?: return copy(apps = apps - intent.host)
    val previous = apps[intent.host]?.takeIf {
        it.surface == target.surface && it.context == intent.expectedContext
    }
    val app = UiApp(
        target.surface,
        intent.expectedContext,
        target.args,
        target.cardId,
        isExpanded = previous?.isExpanded ?: false,
        sequence = previous?.sequence ?: appSequence,
    )
    return copy(apps = apps + (intent.host to app))
}
