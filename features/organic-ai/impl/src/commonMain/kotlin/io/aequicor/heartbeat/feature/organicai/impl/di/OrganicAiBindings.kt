package io.aequicor.heartbeat.feature.organicai.impl.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.profilefacade.ProfileStartup
import io.aequicor.heartbeat.core.statemachine.MachineLauncher
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiEnabled
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiIntent
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiMachineSpec
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiState
import io.aequicor.heartbeat.feature.organicai.impl.domain.CellDriver
import io.aequicor.heartbeat.feature.organicai.impl.domain.CellSessions
import io.aequicor.heartbeat.feature.organicai.impl.domain.DefaultTargets
import io.aequicor.heartbeat.feature.organicai.impl.domain.ImmunityCourt
import io.aequicor.heartbeat.feature.organicai.impl.domain.JudgeSessions
import io.aequicor.heartbeat.feature.organicai.impl.domain.OrganicAiEffects
import io.aequicor.heartbeat.feature.organicai.impl.domain.OrganicAiMachine
import io.aequicor.heartbeat.feature.organicai.impl.domain.OrganismJournal
import io.aequicor.heartbeat.feature.organicai.impl.domain.SessionTranscripts
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Organisms belong to the profile: they develop and are judged while no screen is open. */
@ContributesTo(ProfileScope::class)
@BindingContainer
public object OrganicAiBindings {
    @Provides
    internal fun effects(
        journal: OrganismJournal,
        targets: DefaultTargets,
        cells: CellSessions,
        judges: JudgeSessions,
        transcripts: SessionTranscripts,
    ): OrganicAiEffects = OrganicAiEffects(
        journal = journal,
        targets = targets,
        cells = cells,
        driver = CellDriver(cells, journal),
        court = ImmunityCourt(judges, transcripts),
    )

    @Provides
    @SingleIn(ProfileScope::class)
    internal fun machine(
        launcher: MachineLauncher,
        @ForScope(ProfileScope::class) scope: ScopeHandle,
        effects: OrganicAiEffects,
    ): OrganicAiMachine = launcher.launch(OrganicAiMachineSpec, scope, effects)
}

/**
 * Follows the toggle for the profile: on, the machine wakes the saved organisms (once a previous sleep has ended);
 * off, it saves them, stops their turns and sleeps. The machine is not launched while the toggle stays off.
 */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class OrganicAiStartup(
    private val machine: Lazy<OrganicAiMachine>,
    private val toggles: FeatureToggles,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
) : ProfileStartup {
    private val log = Log.tag("OrganicAiStartup")

    override fun start() {
        profile.coroutineScope.launch {
            toggles.observe(OrganicAiEnabled).distinctUntilChanged().collectLatest { isEnabled ->
                if (isEnabled) awaken() else sleep()
            }
        }
    }

    private suspend fun awaken() {
        val machine = machine.value
        machine.state.first { it == OrganicAiState.Dormant }
        val result = machine.send(OrganicAiIntent.Public.Awaken)
        log.i { "organic AI is on; awakening: $result" }
    }

    private suspend fun sleep() {
        if (!machine.isInitialized()) {
            log.i { "organic AI is off" }
            return
        }
        val result = machine.value.send(OrganicAiIntent.Public.Sleep)
        log.i { "organic AI is off; sleeping: $result" }
    }
}

/** Registers the disabled-by-default organic AI switch. */
@ContributesTo(AppScope::class)
@BindingContainer
public object OrganicAiToggleBindings {
    /** Exact FeatureToggle wildcard joins the application registry. */
    @Provides
    @IntoSet
    public fun toggle(): FeatureToggle<*> = OrganicAiEnabled
}
