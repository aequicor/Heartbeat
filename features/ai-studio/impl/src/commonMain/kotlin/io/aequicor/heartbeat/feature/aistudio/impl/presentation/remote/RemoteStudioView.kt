package io.aequicor.heartbeat.feature.aistudio.impl.presentation.remote

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aistudio.api.StudioRemoteIntent
import io.aequicor.heartbeat.feature.aistudio.api.StudioRemoteReply
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioModel
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.screenmodel.StudioViewJson
import io.aequicor.heartbeat.feature.aistudio.screenmodel.remote.StudioViewEncoder
import io.aequicor.heartbeat.feature.aistudio.screenmodel.remote.StudioViewFrame
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.job
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import pro.respawn.flowmvi.dsl.collect
import kotlin.time.Duration

/**
 * One remote view: a studio screen store of its own ([release] closes its scope), drawn by a host from frames.
 * One host is attached at a time; a newer attachment detaches the older one. Intents are applied in sequence
 * order, each once.
 */
internal class RemoteStudioView(
    val id: String,
    private val release: () -> Unit,
    private val model: AiStudioModel,
    private val encoding: CoroutineDispatcher,
    private val frameInterval: Duration,
) {
    private val log = Log.tag("StudioRemoteView")
    private val attached = MutableStateFlow<Job?>(null)
    private var lastSeq = 0L

    /** Whether a host collects the frames now. */
    val isAttached: Boolean get() = attached.value?.isActive == true

    /** Frames for one attachment: a snapshot first, then deltas at most every [frameInterval]. */
    fun frames(onDetached: () -> Unit): Flow<JsonObject> = channelFlow {
        attached.getAndUpdate { currentCoroutineContext().job }?.cancel()
        log.i { "view ${id.take(SHORT_ID)} attached" }
        val encoder = StudioViewEncoder()
        try {
            // Collecting subscribes to the store, which also starts its subscription-bound observations.
            model.store.collect {
                states.collect { state ->
                    encoder.frame(state, state.remoteSeq)?.let { send(encode(it)) }
                    delay(frameInterval)
                }
            }
        } finally {
            log.i { "view ${id.take(SHORT_ID)} detached" }
            onDetached()
        }
    }.flowOn(encoding)

    /** Applies the host's intents after the last applied one; refused ones are acknowledged too. */
    fun apply(intents: List<StudioRemoteIntent>): StudioRemoteReply {
        val refused = mutableListOf<Long>()
        for (remote in intents.sortedBy { it.seq }) {
            if (remote.seq <= lastSeq) continue
            lastSeq = remote.seq
            val intent = decode(remote)
            if (intent == null || !isAllowedRemotely(intent)) {
                log.i { "intent ${intent?.let { it::class.simpleName } ?: "undecodable"} refused" }
                refused += remote.seq
            } else {
                model.store.intent(intent)
            }
        }
        model.store.intent(AiStudioScreenIntent.RemoteApplied(lastSeq))
        return StudioRemoteReply.Applied(lastSeq, refused)
    }

    /** Stops the view's store and machine. Main thread. */
    fun close() {
        attached.value?.cancel()
        release()
        log.i { "view ${id.take(SHORT_ID)} closed" }
    }

    private fun decode(remote: StudioRemoteIntent): AiStudioScreenIntent? = try {
        StudioViewJson.decodeFromJsonElement(AiStudioScreenIntent.serializer(), remote.intent)
    } catch (e: SerializationException) {
        log.w(e) { "intent ${remote.seq} is not a studio intent" }
        null
    } catch (e: IllegalArgumentException) {
        log.w(e) { "intent ${remote.seq} is not a studio intent" }
        null
    }

    @HighFrequency
    private fun encode(frame: StudioViewFrame): JsonObject =
        StudioViewJson.encodeToJsonElement(StudioViewFrame.serializer(), frame).jsonObject

    private companion object {
        const val SHORT_ID = 8
    }
}
