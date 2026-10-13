package io.aequicor.heartbeat.feature.aistudio.screenmodel.remote

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.MessageUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.StudioViewJson
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.uuid.Uuid

private const val TRANSCRIPT = "t:"
private const val SUB_TRANSCRIPT = "s:"

/**
 * App side of one remote view: turns successive screen states into frames. The first frame and the first after
 * [reset] are snapshots; later frames carry only what changed. Not thread-safe: one view encodes on one coroutine.
 */
class StudioViewEncoder(
    private val json: Json = StudioViewJson,
    private val generation: String = Uuid.random().toString(),
) {
    private var rev = 0L
    private var lastAck = 0L
    private var shell: JsonObject? = null
    private var feeds: Map<String, ImmutableList<MessageUi>> = emptyMap()

    /** The next frame is a snapshot (a panel connected again or lost track). */
    fun reset() {
        shell = null
    }

    /** The frame that brings the panel from the previous frame to [state], or `null` when nothing changed. */
    @HighFrequency
    fun frame(state: AiStudioScreenState, ack: Long): StudioViewFrame? {
        val nextShell = json.encodeToJsonElement(AiStudioScreenState.serializer(), state.shell()).jsonObject
        val nextFeeds = state.feeds()
        val previous = shell
        val frame = if (previous == null) {
            StudioViewFrame.Snapshot(++rev, ack, nextShell, nextFeeds, generation)
        } else {
            val shellChanges = nextShell.filter { (key, value) -> previous[key] != value }
            val feedChanges = feedPatches(feeds, nextFeeds)
            if (shellChanges.isEmpty() && feedChanges.isEmpty() && ack == lastAck) return null
            val base = rev
            StudioViewFrame.Delta(++rev, ack, base, shellChanges, feedChanges)
        }
        shell = nextShell
        feeds = nextFeeds
        lastAck = ack
        return frame
    }
}

/** Panel side of one remote view: rebuilds the screen state from frames, reusing unchanged messages. */
class StudioViewMirror(private val json: Json = StudioViewJson) {
    private var rev: Long? = null
    private var shell = JsonObject(emptyMap())
    private var shellState: AiStudioScreenState? = null
    private var feeds: Map<String, ImmutableList<MessageUi>> = emptyMap()

    /** Revision of the last applied frame, `null` before the first snapshot. */
    val revision: Long? get() = rev

    /**
     * The screen after [frame].
     * @throws StudioViewGapException when a delta does not continue the last applied revision.
     */
    @HighFrequency
    fun apply(frame: StudioViewFrame): AiStudioScreenState {
        when (frame) {
            is StudioViewFrame.Snapshot -> {
                shell = frame.shell
                shellState = json.decodeFromJsonElement(AiStudioScreenState.serializer(), frame.shell)
                feeds = frame.feeds.mapValues { it.value.toImmutableList() }
            }

            is StudioViewFrame.Delta -> {
                val current = rev
                if (current == null || frame.base != current) throw StudioViewGapException(current, frame.base)
                if (frame.shell.isNotEmpty()) {
                    shell = JsonObject(shell + frame.shell)
                    shellState = json.decodeFromJsonElement(AiStudioScreenState.serializer(), shell)
                }
                feeds = applyPatches(feeds, frame.feeds)
            }
        }
        rev = frame.rev
        return checkNotNull(shellState).withFeeds(feeds)
    }
}

/** A delta arrived for another revision than the mirror holds; the view must start over with a snapshot. */
class StudioViewGapException(val held: Long?, val base: Long) :
    IllegalStateException("Studio view delta for revision $base, mirror at ${held ?: "none"}")

private fun AiStudioScreenState.shell(): AiStudioScreenState =
    copy(transcripts = persistentMapOf(), subTranscripts = persistentMapOf())

private fun AiStudioScreenState.feeds(): Map<String, ImmutableList<MessageUi>> =
    transcripts.mapKeys { TRANSCRIPT + it.key } + subTranscripts.mapKeys { SUB_TRANSCRIPT + it.key }

private fun AiStudioScreenState.withFeeds(feeds: Map<String, ImmutableList<MessageUi>>): AiStudioScreenState = copy(
    transcripts = feeds.feedsOf(TRANSCRIPT),
    subTranscripts = feeds.feedsOf(SUB_TRANSCRIPT),
)

private fun Map<String, ImmutableList<MessageUi>>.feedsOf(prefix: String) = entries
    .filter { it.key.startsWith(prefix) }
    .associate { it.key.removePrefix(prefix) to it.value }
    .toImmutableMap()

private fun feedPatches(
    old: Map<String, ImmutableList<MessageUi>>,
    new: Map<String, ImmutableList<MessageUi>>,
): Map<String, FeedPatch> = buildMap {
    for (key in old.keys - new.keys) put(key, FeedPatch.Gone)
    for ((key, messages) in new) {
        val previous = old[key]
        when {
            previous == null -> put(key, FeedPatch.Reset(messages))
            previous !== messages && previous != messages -> put(key, edit(previous, messages))
        }
    }
}

private fun edit(old: List<MessageUi>, new: List<MessageUi>): FeedPatch.Edit {
    val byId = old.associateBy { it.id }
    val newIds = new.map { it.id }
    val kept = newIds.toSet()
    val oldIds = old.map { it.id }
    val removed = oldIds.filter { it !in kept }
    val known = byId.keys
    val expectedOrder = oldIds.filter { it in kept } + newIds.filter { it !in known }
    return FeedPatch.Edit(
        upserts = new.filter { byId[it.id] != it },
        removed = removed,
        order = newIds.takeIf { it != expectedOrder },
    )
}

private fun applyPatches(
    feeds: Map<String, ImmutableList<MessageUi>>,
    patches: Map<String, FeedPatch>,
): Map<String, ImmutableList<MessageUi>> {
    if (patches.isEmpty()) return feeds
    val next = feeds.toMutableMap()
    for ((key, patch) in patches) {
        when (patch) {
            FeedPatch.Gone -> next.remove(key)
            is FeedPatch.Reset -> next[key] = patch.messages.toImmutableList()
            is FeedPatch.Edit -> next[key] = applied(next[key].orEmpty(), patch)
        }
    }
    return next
}

private fun applied(messages: List<MessageUi>, patch: FeedPatch.Edit): ImmutableList<MessageUi> {
    val removed = patch.removed.toSet()
    val upserts = patch.upserts.associateBy { it.id }
    val kept = messages.filter { it.id !in removed }.map { upserts[it.id] ?: it }
    val known = kept.map { it.id }.toSet()
    val merged = kept + patch.upserts.filter { it.id !in known }
    val order = patch.order ?: return merged.toImmutableList()
    val byId = merged.associateBy { it.id }
    return order.mapNotNull { byId[it] }.toImmutableList()
}
