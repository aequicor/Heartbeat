package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.spi.HelperPromptAttempt
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledHelperPromptOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job

/** Resolves only required lazy contributions; metadata comes from the chat host, never a script argument. */
@Inject
internal class StudioHelperPromptAdmissions(
    private val records: Lazy<StudioHelperChatRecords>,
    private val owners: Lazy<Set<ScheduledHelperPromptOwner>>,
) {
    private val log = Log.tag("StudioHelperPromptAdmissions")

    fun decisions(
        helper: HelperId,
        session: SessionRef,
        receipt: StudioHelperReceipt,
        workspace: WorkspaceRef? = null,
    ): Flow<Boolean> = flow {
        val handoff = receipt.handoff
        val feature = handoff?.ownerFeature
        if (feature == null && handoff?.initiator == null) {
            emit(true)
            return@flow
        }
        val metadata = checkNotNull(records.value.helperMetadata(helper)) { "Helper metadata unavailable" }
        val attempt = HelperPromptAttempt(metadata, session, receipt.request, handoff, workspace)
        emitAll(combined(attempt, selectedOwners(feature, handoff.initiator != null)))
    }.catch { error ->
        if (error is CancellationException || error !is Exception) throw error
        log.w(IllegalStateException("Helper prompt admission failed (${error::class.simpleName.orEmpty()})")) {
            "Reject helper prompt with unavailable context admission"
        }
        emit(false)
    }

    private fun selectedOwners(feature: String?, hasInitiator: Boolean): List<ScheduledHelperPromptOwner> = buildList {
        if (feature != null) {
            add(
                checkNotNull(owners.value.singleOrNull { it.feature == feature }) {
                    "Helper prompt owner is missing or ambiguous"
                },
            )
        }
        if (hasInitiator) addAll(owners.value.filter { it.isObservingInitiators })
    }.distinct()

    private fun combined(attempt: HelperPromptAttempt, selected: List<ScheduledHelperPromptOwner>): Flow<Boolean> =
        flow {
            if (selected.isEmpty()) {
                emit(true)
            } else {
                coroutineScope {
                    val parent = coroutineContext.job
                    val sources = selected.map { owner ->
                        owner.admission(attempt).onCompletion { error ->
                            if (error is CancellationException && currentCoroutineContext().isActive) {
                                parent.cancel(error)
                            }
                        }
                    }
                    emitAll(combine(sources) { decisions -> decisions.all { it } })
                }
            }
        }
}
