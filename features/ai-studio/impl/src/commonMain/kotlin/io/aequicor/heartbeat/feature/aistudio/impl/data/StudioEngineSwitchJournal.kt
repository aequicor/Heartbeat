package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aistudio.api.StudioSessionSettings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Written before transcript changes. Recovery repeats the same freeze and commits the same route. */
@Serializable
internal data class StudioEngineSwitchJournal(
    val source: SessionRef?,
    val expected: EngineTarget,
    val target: EngineTarget,
    val settings: StudioSessionSettings,
    val segment: Int,
    val fromLabel: String,
    val toLabel: String,
    val harnesses: Set<String>?,
    val harnessSource: SessionRef?,
    val helper: StudioHelperIdentity?,
) {
    val boundary: String get() = "$FROZEN_PREFIX$segment/engine-switch"

    override fun toString(): String = "StudioEngineSwitchJournal(segment=$segment)"
}

/** Host-owned notice payload; translated by the screen rather than written as localized transcript text. */
@Serializable
internal data class StudioEngineSwitchNotice(val from: String, val to: String, val isTruncated: Boolean = false)

/** Idempotent across a crash after the Room transaction but before the chat's KV commit. */
internal fun List<SessionItem>.frozenForSwitch(journal: StudioEngineSwitchJournal): List<SessionItem> {
    if (any { it.info.id.value == journal.boundary }) return this
    val frozen = frozen(journal.segment).dropLast(1)
    val notice = StudioEngineSwitchNotice(journal.fromLabel, journal.toLabel)
    return frozen + SessionItem.Notice(
        ItemInfo(ItemId(journal.boundary), size.toLong(), 0),
        Json.encodeToString(notice),
    )
}

/** One KV write commits the new route and receipt while preserving unrelated edits to the chat. */
internal fun StudioChatRecord.committedSwitch(journal: StudioEngineSwitchJournal): StudioChatRecord {
    check(ref == journal.source && target == journal.expected) { "The journal no longer owns the source route" }
    return copy(
        ref = null,
        target = journal.target,
        configuration = journal.settings,
        previousRefs = (previousRefs + listOfNotNull(ref)).distinct(),
        contextSegment = journal.segment,
        pendingEngineContext = journal.boundary,
        pendingEngineSwitch = null,
        pendingSeed = null,
        pendingBrief = null,
        pendingHarnesses = journal.harnesses,
        pendingHarnessSource = journal.harnessSource,
        pendingHarnessContext = null,
        helper = journal.helper,
    )
}
