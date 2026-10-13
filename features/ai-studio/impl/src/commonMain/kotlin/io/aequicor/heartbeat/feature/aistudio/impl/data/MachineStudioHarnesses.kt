package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.MachineRef
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioHarness
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioHarnessAttach
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioHarnessChoices
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioHarnesses
import io.aequicor.heartbeat.feature.aistudio.impl.domain.ToolChoices
import io.aequicor.heartbeat.feature.harness.api.HarnessEnabled
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessIntent
import io.aequicor.heartbeat.feature.harness.api.HarnessMachineKey
import io.aequicor.heartbeat.feature.harness.api.HarnessMutation
import io.aequicor.heartbeat.feature.harness.api.HarnessOutput
import io.aequicor.heartbeat.feature.harness.api.HarnessRejection
import io.aequicor.heartbeat.feature.harness.api.HarnessScope
import io.aequicor.heartbeat.feature.harness.api.HarnessState
import io.aequicor.heartbeat.feature.harness.api.HarnessTools
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Chat side of the harness library. Existing chats are connected through the harness machine at once. A new
 * chat's choice is claimed under its submission id and connected by that submission's first turn before the prompt
 * reaches the engine, so the first turn already sees the harness; other turns never wait for it. The library takes
 * one attachment write at a time, so connections are written one after another. Titles are never logged.
 */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class, binding = binding<StudioHarnesses>())
@ContributesBinding(ProfileScope::class, binding = binding<StudioHarnessAttach>())
@Inject
internal class MachineStudioHarnesses(private val machines: MachineRegistry, private val toggles: FeatureToggles) :
    StudioHarnesses,
    StudioHarnessAttach {
    private val log = Log.tag("StudioHarnesses")

    /** Harness choices by submission id, oldest first; submissions that never reach a turn are evicted. */
    private val claims = MutableStateFlow<Map<String, Set<String>>>(emptyMap())

    /** The library persists one attachment at a time; bulk selection must not race its own writes. */
    private val connectionWrites = Mutex()

    @OptIn(ExperimentalCoroutinesApi::class)
    override val choices: Flow<StudioHarnessChoices> = toggles.observe(HarnessEnabled).flatMapLatest { isEnabled ->
        if (isEnabled) {
            machines.observe(HarnessMachineKey).flatMapLatest { it?.state ?: flowOf(null) }
        } else {
            flowOf(null)
        }
    }.map { state ->
        val ready = (state as? HarnessState.Ready)?.takeUnless { it.isSuspended } ?: return@map StudioHarnessChoices()
        StudioHarnessChoices(
            harnesses = ready.harnesses.asSequence().map { it.harness }
                .filter { it.isEnabled && ready.pending[it.id] !is HarnessMutation.Remove }
                .sortedBy { it.name.value }
                .map { harness ->
                    StudioHarness(
                        harness.id.value,
                        harness.title,
                        harness.description,
                        (harness.scope as? HarnessScope.Projects)?.projects?.mapTo(
                            mutableSetOf(),
                        ) { it.value }.orEmpty(),
                    )
                }.toList(),
            attached = ready.attachments.mapValues { (_, ids) -> ids.mapTo(mutableSetOf()) { it.value } },
        )
    }

    override suspend fun connect(session: SessionRef, harness: String, isSelected: Boolean): Boolean {
        val library = library() ?: return false
        val isConfirmed = write(library) { request ->
            if (isSelected) {
                HarnessIntent.Public.Attach(request, HarnessId(harness), session)
            } else {
                HarnessIntent.Public.Detach(request, HarnessId(harness), session)
            }
        } == Written.Confirmed
        if (isConfirmed) {
            log.i { "Chat harness connection changed attach=$isSelected" }
        } else {
            log.w { "Chat harness connection was not confirmed attach=$isSelected" }
        }
        return isConfirmed
    }

    override fun claim(submissionId: String, harnesses: Set<String>) {
        if (submissionId.isEmpty() || harnesses.isEmpty()) return
        claims.update { current -> (current - submissionId + (submissionId to harnesses)).newest() }
        log.d { "New chat submission claims its harness choice count=${harnesses.size}" }
    }

    override fun preselected(submissionId: String): ToolChoices =
        claims.value[submissionId]?.let { mapOf(HarnessTools.GROUP to it) }.orEmpty()

    override fun release(submissionId: String) {
        if (submissionId !in claims.value) return
        claims.update { it - submissionId }
        log.v { "Harness choice claim released" }
    }

    /**
     * Failures leave the chat without the harness instead of failing the turn. The first prompt waits at most
     * [SUBMIT_DEADLINE] for all connections, and a library that leaves one write unanswered gets no further writes.
     * The deadline bounds only the wait, not the write: an Attach the library already took may still be applied
     * after the first prompt, so that prompt can run without the harness that later turns of the chat have.
     */
    override suspend fun beforeSubmit(submissionId: String, session: SessionRef) {
        val claimed = claims.getAndUpdate { it - submissionId }[submissionId] ?: return
        val library = library()
        if (library == null) {
            log.w { "Harnesses chosen for a new chat were not connected: the library is not running" }
            return
        }
        var connected = 0
        val isAnswered = withTimeoutOrNull(SUBMIT_DEADLINE) {
            for (harness in claimed) {
                when (write(library) { HarnessIntent.Public.Attach(it, HarnessId(harness), session) }) {
                    Written.Confirmed -> connected++
                    Written.Refused -> Unit
                    Written.Unanswered -> return@withTimeoutOrNull false
                }
            }
            true
        } == true
        if (connected == claimed.size) {
            log.i { "Harnesses connected before the first prompt count=$connected" }
        } else {
            log.w {
                "Harnesses chosen for a new chat were not all connected connected=$connected of=${claimed.size} " +
                    "answered=$isAnswered"
            }
        }
    }

    private suspend fun library(): Library? =
        if (toggles.get(HarnessEnabled)) machines.find(HarnessMachineKey) else null

    /**
     * Writes one connection and waits for its receipt. The library holds a single attachment write, so a busy
     * library is retried once the write in flight has finished. A write the library did not take, did not answer
     * or that stayed busy past [CONFIRMATION] is [Written.Unanswered].
     */
    private suspend fun write(library: Library, change: (RequestId) -> HarnessIntent.Public): Written =
        connectionWrites.withLock { writeLocked(library, change) }

    private suspend fun writeLocked(library: Library, change: (RequestId) -> HarnessIntent.Public): Written {
        var attempts = 0
        var receipt: HarnessOutput?
        var isWritable = true
        do {
            attempts++
            receipt = submit(library, change(RequestId(Uuid.random().toHexString())))
            val isBusy = receipt is HarnessOutput.Rejected && receipt.reason == HarnessRejection.Busy
            if (isBusy && attempts < WRITE_ATTEMPTS) isWritable = awaitWritable(library)
        } while (isBusy && attempts < WRITE_ATTEMPTS && isWritable)
        return when {
            receipt is HarnessOutput.Attached || receipt is HarnessOutput.Detached -> Written.Confirmed
            receipt == null || !isWritable -> Written.Unanswered
            else -> Written.Refused
        }
    }

    private suspend fun awaitWritable(library: Library): Boolean {
        log.d { "Harness library is busy with another connection; retry after it" }
        return withTimeoutOrNull(CONFIRMATION) {
            library.state.first { state ->
                state is HarnessState.Ready && state.attachmentWrite == null &&
                    state.pending.values.none { it is HarnessMutation.Remove }
            }
        } != null
    }

    private suspend fun submit(library: Library, intent: HarnessIntent.Public): HarnessOutput? = coroutineScope {
        val receipt = async(start = CoroutineStart.UNDISPATCHED) {
            library.outputs.first {
                (it is HarnessOutput.Attached && it.requestId == intent.requestId) ||
                    (it is HarnessOutput.Detached && it.requestId == intent.requestId) ||
                    (it is HarnessOutput.Rejected && it.requestId == intent.requestId) ||
                    (it is HarnessOutput.StorageFailed && it.requestId == intent.requestId)
            }
        }
        try {
            if (library.send(intent) == SendResult.Accepted) {
                withTimeoutOrNull(CONFIRMATION) { receipt.await() }
            } else {
                null
            }
        } finally {
            receipt.cancel()
        }
    }
}

/** How one connection write ended. */
private enum class Written { Confirmed, Refused, Unanswered }

/** Keeps the newest claims; insertion order is preserved by the map. */
private fun Map<String, Set<String>>.newest(): Map<String, Set<String>> =
    if (size <= CLAIMS_KEPT) this else entries.drop(size - CLAIMS_KEPT).associate { it.key to it.value }

private typealias Library = MachineRef<HarnessState, HarnessIntent.Public, HarnessOutput>

private val CONFIRMATION = 3.seconds
private val SUBMIT_DEADLINE = 10.seconds
private const val WRITE_ATTEMPTS = 5
private const val CLAIMS_KEPT = 16
