package io.aequicor.heartbeat.feature.harness.impl.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.secrets.SecretStore
import io.aequicor.heartbeat.core.statemachine.MachineLauncher
import io.aequicor.heartbeat.feature.harness.api.HarnessBackgroundWorker
import io.aequicor.heartbeat.feature.harness.api.HarnessEnabled
import io.aequicor.heartbeat.feature.harness.api.HarnessMachineSpec
import io.aequicor.heartbeat.feature.harness.impl.data.decision.HarnessProcessCommandsEnabled
import io.aequicor.heartbeat.feature.harness.impl.data.ownership.HarnessProcessOwnershipEnabled
import io.aequicor.heartbeat.feature.harness.impl.data.ownership.HarnessScriptOwnershipFactory
import io.aequicor.heartbeat.feature.harness.impl.data.run.HarnessIsolatedOrganisms
import io.aequicor.heartbeat.feature.harness.impl.data.runtime.StagedHarnessContexts
import io.aequicor.heartbeat.feature.harness.impl.data.services.HarnessCommandsEnabled
import io.aequicor.heartbeat.feature.harness.impl.data.services.HarnessGitHubEnabled
import io.aequicor.heartbeat.feature.harness.impl.data.services.HarnessScriptCommandsFactory
import io.aequicor.heartbeat.feature.harness.impl.data.services.HarnessScriptGitHubFactory
import io.aequicor.heartbeat.feature.harness.impl.data.services.ProtectedHarnessData
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessEffects
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessLibraryStorage
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessMachine
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessRunStorage
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessRuntimeControl
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.runtime.HarnessOwnershipRuntime
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigins
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessRuntimeContextFactory
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessDataStorage
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessDurableProcessesEnabled
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessProcessArchiveStorage
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessScriptSchedulerFactory
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessScriptSessionsFactory
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessScriptWorkflowsFactory
import kotlin.time.Clock

/** Constructs a single profile-owned library without eagerly starting it when the toggle is disabled. */
@ContributesTo(ProfileScope::class)
@BindingContainer
public object HarnessBindings {
    /** Production always supplies the profile fence; optional constructor compatibility is test-only. */
    @Provides
    @SingleIn(ProfileScope::class)
    internal fun data(
        secrets: SecretStore,
        archives: HarnessProcessArchiveStorage,
        ownership: HarnessOwnershipRuntime,
    ): HarnessDataStorage = ProtectedHarnessData(secrets, archives, ownership)

    /** A missing ownership factory is a graph error instead of silently disabling the public script service. */
    @Provides
    @SingleIn(ProfileScope::class)
    internal fun contexts(
        origins: HarnessCallOrigins,
        schedulers: HarnessScriptSchedulerFactory,
        workflows: HarnessScriptWorkflowsFactory,
        data: HarnessDataStorage,
        runs: HarnessRunStorage,
        commands: HarnessScriptCommandsFactory,
        github: HarnessScriptGitHubFactory,
        clock: Clock,
        toggles: FeatureToggles,
        ownership: HarnessScriptOwnershipFactory,
        sessions: HarnessScriptSessionsFactory,
    ): HarnessRuntimeContextFactory = StagedHarnessContexts(
        origins, schedulers, workflows, data, runs, commands, github, clock, toggles, ownership, sessions,
    )

    @Provides
    @SingleIn(ProfileScope::class)
    internal fun machine(
        launcher: MachineLauncher,
        @ForScope(ProfileScope::class) scope: ScopeHandle,
        storage: HarnessLibraryStorage,
        runtime: HarnessRuntimeControl,
    ): HarnessMachine = launcher.launch(HarnessMachineSpec, scope, HarnessEffects(storage, runtime))
}

/** Registers the feature flag with its exact wildcard multibinding type. */
@ContributesTo(AppScope::class)
@BindingContainer
public object HarnessToggleBindings {
    /** Exact wildcard type contributes the flag to the application registry. */
    @Provides
    @IntoSet
    public fun enabled(): FeatureToggle<*> = HarnessEnabled

    /** Explicit no-window host; does not change any permission or execution toggle. */
    @Provides
    @IntoSet
    public fun backgroundWorker(): FeatureToggle<*> = HarnessBackgroundWorker

    /** Trusted sandbox context for workflow organism stages. */
    @Provides
    @IntoSet
    public fun isolatedOrganisms(): FeatureToggle<*> = HarnessIsolatedOrganisms

    /** Mini-app commands of approved scripts. */
    @Provides
    @IntoSet
    public fun commands(): FeatureToggle<*> = HarnessCommandsEnabled

    /** Opt-in recoverable GitHub adapter with native host admission. */
    @Provides
    @IntoSet
    public fun github(): FeatureToggle<*> = HarnessGitHubEnabled

    /** Versioned multi-stage execution and archival of protected process state. */
    @Provides
    @IntoSet
    public fun durableProcesses(): FeatureToggle<*> = HarnessDurableProcessesEnabled

    /** Explicit host-mediated transfer between executor profiles. */
    @Provides
    @IntoSet
    public fun processOwnership(): FeatureToggle<*> = HarnessProcessOwnershipEnabled

    /** Exact host-owned decisions for process commands. */
    @Provides
    @IntoSet
    public fun processCommands(): FeatureToggle<*> = HarnessProcessCommandsEnabled
}
