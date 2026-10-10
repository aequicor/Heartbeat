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
import io.aequicor.heartbeat.feature.harness.api.HarnessEnabled
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessIntent
import io.aequicor.heartbeat.feature.harness.api.HarnessMachineKey
import io.aequicor.heartbeat.feature.harness.api.HarnessMutation
import io.aequicor.heartbeat.feature.harness.api.HarnessOutput
import io.aequicor.heartbeat.feature.harness.api.HarnessScope
import io.aequicor.heartbeat.feature.harness.api.HarnessState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Chat side of the harness library. Existing chats are connected through the harness machine at once. A new
 * chat's choice is claimed by its submission and connected before that first prompt reaches the engine, so its
 * first turn already sees the harness; only turns racing such a claim wait for the chat to be named.
 * Titles are never logged.
 */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class, binding = binding<StudioHarnesses>())
@ContributesBinding(ProfileScope::class, binding = binding<StudioHarnessAttach>())
@Inject
internal class MachineStudioHarnesses(private val machines: MachineRegistry, private val toggles: FeatureToggles) :
    StudioHarnesses,
    StudioHarnessAttach {
    private val log = Log.tag("StudioHarnesses")
    private val claims = MutableStateFlow<Map<String, Claim>>(emptyMap())

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
                        isProfile = harness.scope == HarnessScope.Profile,
                        projects = (harness.scope as? HarnessScope.Projects)?.projects.orEmpty()
                            .mapTo(mutableSetOf()) { it.value },
                    )
                }.toList(),
            attached = ready.attachments.mapValues { (_, ids) -> ids.mapTo(mutableSetOf()) { it.value } },
        )
    }

    override suspend fun connect(session: SessionRef, harness: String, isSelected: Boolean): Boolean {
        val id = HarnessId(harness)
        val request = RequestId(Uuid.random().toHexString())
        val intent = if (isSelected) {
            HarnessIntent.Public.Attach(request, id, session)
        } else {
            HarnessIntent.Public.Detach(request, id, session)
        }
        val library = library() ?: return false
        val isConfirmed = submit(library, intent)
        if (isConfirmed) {
            log.i { "Chat harness connection changed attach=$isSelected" }
        } else {
            log.w { "Chat harness connection was not confirmed attach=$isSelected" }
        }
        return isConfirmed
    }

    override fun claim(submissionId: String, harnesses: Set<String>) {
        if (harnesses.isEmpty()) return
        claims.update { it + (submissionId to Claim(harnesses)) }
        log.d { "New chat submission claims its harness choice count=${harnesses.size}" }
    }

    override fun bindSubmission(submissionId: String, chatId: String) {
        val claim = claims.value[submissionId] ?: return
        claims.update { it + (submissionId to claim.copy(chatId = chatId)) }
        log.v { "Claimed harness choice bound to its chat" }
    }

    override fun release(submissionId: String) {
        if (submissionId !in claims.value) return
        claims.update { it - submissionId }
        log.v { "Harness choice claim released" }
    }

    /**
     * Turns wait only while a claimed submission has not named its chat yet; the chat's claim is then connected
     * and dropped. Failures leave the chat without the harness instead of failing the turn.
     */
    override suspend fun beforeSubmit(chatId: String, session: SessionRef) {
        if (claims.value.isEmpty()) return
        val claimed = withTimeoutOrNull(HANDOVER) {
            claims.first { current ->
                current.values.any { it.chatId == chatId } ||
                    current.values.none { it.chatId == null }
            }
        }?.entries?.firstOrNull { it.value.chatId == chatId } ?: return
        claims.update { it - claimed.key }
        val library = library() ?: return
        val confirmed = coroutineScope {
            claimed.value.harnesses.map { harness ->
                async {
                    val request = RequestId(Uuid.random().toHexString())
                    submit(library, HarnessIntent.Public.Attach(request, HarnessId(harness), session))
                }
            }.awaitAll().count { it }
        }
        if (confirmed == claimed.value.harnesses.size) {
            log.i { "Harnesses connected before the first prompt count=$confirmed" }
        } else {
            val chosen = claimed.value.harnesses.size
            log.w { "Harnesses chosen for a new chat were not all connected connected=$confirmed of=$chosen" }
        }
    }

    private suspend fun library(): Library? =
        if (toggles.get(HarnessEnabled)) machines.find(HarnessMachineKey) else null

    private suspend fun submit(library: Library, intent: HarnessIntent.Public): Boolean = coroutineScope {
        val receipt = async(start = CoroutineStart.UNDISPATCHED) {
            library.outputs.first {
                (it is HarnessOutput.Attached && it.requestId == intent.requestId) ||
                    (it is HarnessOutput.Detached && it.requestId == intent.requestId) ||
                    (it is HarnessOutput.Rejected && it.requestId == intent.requestId) ||
                    (it is HarnessOutput.StorageFailed && it.requestId == intent.requestId)
            }
        }
        try {
            library.send(intent) == SendResult.Accepted && withTimeoutOrNull(CONFIRMATION) { receipt.await() }.let {
                it is HarnessOutput.Attached || it is HarnessOutput.Detached
            }
        } finally {
            receipt.cancel()
        }
    }
}

/** A new chat's choice in flight; [chatId] is known once the submission created the chat. */
private data class Claim(val harnesses: Set<String>, val chatId: String? = null)

private typealias Library = MachineRef<HarnessState, HarnessIntent.Public, HarnessOutput>

private val HANDOVER = 2.seconds
private val CONFIRMATION = 3.seconds
