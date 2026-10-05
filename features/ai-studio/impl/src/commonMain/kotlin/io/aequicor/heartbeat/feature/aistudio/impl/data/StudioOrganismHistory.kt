package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.profilefacade.ProfileStartup
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiMachineKey
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiState
import io.aequicor.heartbeat.feature.organicai.api.Organism
import io.aequicor.heartbeat.feature.organicai.api.OrganismSession
import io.aequicor.heartbeat.feature.organicai.api.isAlive
import io.aequicor.heartbeat.feature.organicai.api.isDeveloping
import io.aequicor.heartbeat.feature.organicai.api.sessionOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Records every organism's cells and judges while the profile is open, including sub-sessions never shown on
 * screen. Existing ended sessions stay dormant until viewed. A newly discovered ended session gets one snapshot,
 * so even a short judge missed between state observations is saved. Native readers finish before their successors.
 */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class StudioOrganismHistory(
    private val machines: MachineRegistry,
    private val viewer: Lazy<StudioSessionViewer>,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
) : ProfileStartup {
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun start() {
        profile.coroutineScope.launch {
            val states = machines.observe(OrganicAiMachineKey).flatMapLatest { machine ->
                machine?.state?.map { it as? OrganicAiState.Living } ?: flowOf(null)
            }
            recordOrganismHistory(states) { session, isLive ->
                viewer.value.record(session.ref, session.reopening, isLive)
            }
        }
    }
}

/**
 * Reconciles profile readers without restarting them for unrelated changes to an organism. A live reader that
 * returns after handling an engine failure retries independently of state updates; ended sessions are read once.
 */
internal suspend fun recordOrganismHistory(
    states: Flow<OrganicAiState.Living?>,
    record: suspend (OrganismSession, Boolean) -> Unit,
): Unit = coroutineScope {
    val running = mutableMapOf<OrganismSession, Pair<Boolean, Job>>()
    var previous: Map<OrganismSession, Boolean>? = null
    states.map { state -> state?.organisms?.values?.flatMap { it.recordings() }?.toMap() }
        .distinctUntilChanged()
        .collect { current ->
            running.keys.toList().forEach { session ->
                val (isLive, job) = running.getValue(session)
                if (current?.get(session) != isLive) {
                    job.cancelAndJoin()
                    running.remove(session)
                }
            }
            current.orEmpty().forEach { (session, isLive) ->
                val isNewEnded = previous != null && session !in previous.orEmpty()
                val hasEnded = previous?.get(session) == true && !isLive
                val isRecordingRequired = isLive || isNewEnded || hasEnded
                if (session !in running && isRecordingRequired) {
                    running[session] = isLive to launch {
                        recordSessionHistory(session, isLive, record)
                    }
                }
            }
            previous = current
        }
}

private suspend fun recordSessionHistory(
    session: OrganismSession,
    isLive: Boolean,
    record: suspend (OrganismSession, Boolean) -> Unit,
) {
    do {
        record(session, isLive)
        if (isLive) delay(HISTORY_RETRY_MILLIS)
    } while (isLive)
}

/** Cells retain their reopen route; judges never inherit a project or the organism's hosted tools. */
private fun Organism.recordings(): List<Pair<OrganismSession, Boolean>> =
    cells.mapNotNull { cell -> sessionOf(cell.id.value)?.let { it to cell.isAlive } } +
        trials.mapNotNull { trial ->
            sessionOf(trial.case.id.value)?.let { session ->
                session to (isDeveloping && cases.any { it.id == trial.case.id })
            }
        }

private const val HISTORY_RETRY_MILLIS = 2_000L
