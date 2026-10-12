package io.aequicor.heartbeat.feature.serviceasui.api

import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineState
import kotlinx.serialization.json.JsonObject

/** Who asked for a library write: the agent through its tools, the user in settings, or the feature itself. */
public enum class UiWriteOrigin { Agent, User, System }

/** A library write with a caller-chosen unique [id]; its outcome comes back as an output with the same id. */
public data class UiWrite(val id: String, val origin: UiWriteOrigin, val op: UiWriteOp) {
    override fun toString(): String = "UiWrite(id=$id, origin=$origin, op=${op::class.simpleName ?: "UiWriteOp"})"
}

/** A write being saved: committed to the library only after storage confirms [result]. */
public data class UiPendingWrite(val write: UiWrite, val result: UiWriteResult.Applied)

/** A surface open in a dock: opened from [host], whose context it uses, with [args]; [sequence] orders openings. */
public data class UiPanel(
    val surface: UiSurfaceId,
    val host: UiHostId,
    val args: JsonObject = JsonObject(emptyMap()),
    val sequence: Long,
) {
    override fun toString(): String = "UiPanel(surface=${surface.value}, host=${host.value}, sequence=$sequence)"
}

/** Why a library write was refused. */
public enum class UiEditRejection {
    /** The surface changed meanwhile, or a creation found it present. */
    Conflict,

    /** The surface does not exist. */
    Missing,

    /** The library limits would be exceeded. */
    Limit,

    /** Another write is being saved; retry. */
    Busy,

    /** Storage failed; nothing changed. */
    Storage,

    /** The feature is off or its library is not loaded. */
    Unavailable,
}

/**
 * Profile machine of agent interfaces: the surface library, the places hosts bind and the panels open in their
 * docks. [hosts] and [owners] (last snapshot of every provider kind) survive every state.
 */
public sealed interface ServiceUiState : MachineState {
    /** Places bound by hosts, with their context. */
    public val hosts: Map<UiHostId, UiHostContext>

    /** Last owner snapshot of every provider, by kind. */
    public val owners: Map<String, UiOwners>

    /** The feature toggle is off or not resolved yet. */
    public data class Disabled(
        override val hosts: Map<UiHostId, UiHostContext> = emptyMap(),
        override val owners: Map<String, UiOwners> = emptyMap(),
    ) : ServiceUiState

    /** Reading the library; [attempt] counts reads that failed before. */
    public data class Loading(
        override val hosts: Map<UiHostId, UiHostContext> = emptyMap(),
        override val owners: Map<String, UiOwners> = emptyMap(),
        val attempt: Int = 0,
    ) : ServiceUiState

    /** The library could not be read after [attempt] reads; retried automatically a few times, then on `Reload`. */
    public data class Failed(
        override val hosts: Map<UiHostId, UiHostContext> = emptyMap(),
        override val owners: Map<String, UiOwners> = emptyMap(),
        val attempt: Int,
    ) : ServiceUiState

    /**
     * The committed [library] and open [panels] by dock. At most one [write] is being saved; owners whose deletion
     * was confirmed meanwhile wait in [purge]. [writes] numbers the feature's own writes, [panelSequence] openings.
     */
    public data class Ready(
        override val hosts: Map<UiHostId, UiHostContext> = emptyMap(),
        override val owners: Map<String, UiOwners> = emptyMap(),
        val library: UiLibrary = UiLibrary(),
        val panels: Map<UiDockId, UiPanel> = emptyMap(),
        val write: UiPendingWrite? = null,
        val purge: Set<UiOwner> = emptySet(),
        val writes: Long = 0,
        val panelSequence: Long = 0,
    ) : ServiceUiState
}

/** Commands to the machine. */
public sealed interface ServiceUiIntent : MachineIntent {
    /** Sent by hosts (ai-studio) and the feature's own screens. */
    public sealed interface Public : ServiceUiIntent {
        /** Binds or rebinds the place [host] to [context]; surfaces there follow it. */
        public data class BindHost(val host: UiHostId, val context: UiHostContext) : Public

        /** Forgets the place [host]; panels it opened close. */
        public data class UnbindHost(val host: UiHostId) : Public

        /** Opens [surface] in the dock of [host] with [args], replacing the panel open there. */
        public data class OpenPanel(
            val host: UiHostId,
            val surface: UiSurfaceId,
            val args: JsonObject = JsonObject(emptyMap()),
        ) : Public {
            override fun toString(): String = "OpenPanel(host=${host.value}, surface=${surface.value}, args=***)"
        }

        /** Closes the panel of [dock]. */
        public data class ClosePanel(val dock: UiDockId) : Public

        /** Reads the library again after it failed to load. */
        public data object Reload : Public
    }

    /** Sent by the feature itself: startup observers, effects, agent tools and screens. */
    public sealed interface Internal : ServiceUiIntent {
        /** The feature toggle changed. */
        public data class AvailabilityChanged(val isEnabled: Boolean) : Internal

        /** The stored library was read. */
        public data class Loaded(val library: UiLibrary) : Internal

        /** Reading the library failed. */
        public data object LoadFailed : Internal

        /** The pause before another read is over. */
        public data object RetryLoad : Internal

        /** The provider of [kind] reported its [owners]. */
        public data class OwnersChanged(val kind: String, val owners: UiOwners) : Internal

        /** Applies [write] to the library, saving it before the outcome is reported. */
        public data class Edit(val write: UiWrite) : Internal

        /** Storage saved the write [writeId]. */
        public data class Persisted(val writeId: String) : Internal

        /** Storage failed to save the write [writeId]. */
        public data class PersistFailed(val writeId: String) : Internal

        /** A surface in [host] asks the host to put [text] into its composer, or to send it when [isSubmitted]. */
        public data class RequestPrompt(val host: UiHostId, val text: String, val isSubmitted: Boolean) : Internal {
            override fun toString(): String = "RequestPrompt(host=${host.value}, text=***, isSubmitted=$isSubmitted)"
        }
    }
}

/** Storage work of the machine. */
public sealed interface ServiceUiEffect : MachineEffect {
    /** Reads the stored library. */
    public data object Load : ServiceUiEffect

    /** Waits before read number [attempt] + 1. */
    public data class RetryLoad(val attempt: Int) : ServiceUiEffect

    /** Saves [library], the result of the write [writeId]. */
    public data class Persist(val writeId: String, val library: UiLibrary) : ServiceUiEffect
}

/** One-shot events for writers and hosts. */
public sealed interface ServiceUiOutput : MachineOutput {
    /** The write [writeId] was saved; [surface] is the changed surface, [removed] the deleted ones. */
    public data class EditApplied(
        val writeId: String,
        val surface: UiSurface?,
        val removed: Set<UiSurfaceId> = emptySet(),
    ) : ServiceUiOutput

    /** The write [writeId] changed nothing, for [reason]. */
    public data class EditRejected(val writeId: String, val reason: UiEditRejection) : ServiceUiOutput

    /** The host of [host] should put [text] into its composer, or send it when [isSubmitted]. */
    public data class PromptRequested(val host: UiHostId, val text: String, val isSubmitted: Boolean) :
        ServiceUiOutput {
        override fun toString(): String = "PromptRequested(host=${host.value}, text=***, isSubmitted=$isSubmitted)"
    }
}

/** Address of the profile machine of agent interfaces. */
public object ServiceUiMachineKey :
    MachineKey<ServiceUiState, ServiceUiIntent, ServiceUiIntent.Public, ServiceUiEffect, ServiceUiOutput> {
    override val name: String = "service_ui"
}
