package io.aequicor.heartbeat.feature.planningmode.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.planningmode.api.Plan
import io.aequicor.heartbeat.feature.planningmode.api.PlanChat
import kotlinx.serialization.Serializable

/**
 * Metadata of the profile's plans: records without their text and session bindings. Texts live in files so the
 * user can read and edit them outside the studio.
 */
@Serializable
internal data class PlanIndex(val plans: List<Plan> = emptyList(), val bindings: List<PlanBinding> = emptyList()) {
    override fun toString(): String = "PlanIndex(plans=${plans.size}, bindings=${bindings.size})"
}

/** [session] serves [chat]. */
@Serializable
internal data class PlanBinding(val session: SessionRef, val chat: PlanChat)

/** Text of a plan file; [isExternal] when it differs from what the studio last wrote. */
internal data class PlanFileText(val text: String, val isExternal: Boolean) {
    override fun toString(): String = "PlanFileText(length=${text.length}, isExternal=$isExternal)"
}

/** The index of the profile's plans. */
internal interface PlanIndexStore {
    /** The stored index, or null when nothing was saved; an unreadable index fails instead of reading as empty. */
    suspend fun load(): PlanIndex?

    /** Replaces the stored index. */
    suspend fun save(index: PlanIndex)
}

/** Plan texts, one file per chat in the profile directory. */
internal interface PlanFiles {
    /** Text of the plan of [chat], or null when its file does not exist. */
    suspend fun read(chat: PlanChat): PlanFileText?

    /** Atomically replaces the plan file of [chat]. */
    suspend fun write(chat: PlanChat, text: String)

    /** Deletes the files of [chat]. */
    suspend fun delete(chat: PlanChat)
}
