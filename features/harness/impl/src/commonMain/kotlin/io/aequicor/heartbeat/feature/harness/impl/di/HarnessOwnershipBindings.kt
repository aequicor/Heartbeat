package io.aequicor.heartbeat.feature.harness.impl.di

import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.harness.impl.data.decision.GitHubProcessAuthorityFactory
import io.aequicor.heartbeat.feature.harness.impl.data.ownership.ProfileOwnershipRuntime
import io.aequicor.heartbeat.feature.harness.impl.data.ownership.ProtectedOwnershipJournal
import io.aequicor.heartbeat.feature.harness.impl.data.ownership.github.ProtectedImportedGitHub
import io.aequicor.heartbeat.feature.harness.impl.data.services.HarnessCommandSlots
import io.aequicor.heartbeat.feature.harness.impl.data.services.HarnessScriptCommandsFactory
import io.aequicor.heartbeat.feature.harness.impl.data.services.HarnessScriptGitHubFactory
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.HarnessCreationScope
import io.aequicor.heartbeat.feature.harness.impl.domain.decision.HarnessProcessCommandHost
import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubTransport
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.runtime.HarnessOwnershipRuntime
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigins
import io.aequicor.heartbeat.feature.scheduler.api.LocalCommands
import kotlin.time.Clock

/** Required native edges prevent nullable legacy constructor defaults from silently disabling production fencing. */
@ContributesTo(ProfileScope::class)
@BindingContainer
public object HarnessOwnershipBindings {
    /** One concrete profile runtime backs both script transfer and protected admission. */
    @Provides
    @SingleIn(ProfileScope::class)
    internal fun ownershipRuntime(
        journal: ProtectedOwnershipJournal,
        transport: GitHubTransport,
        clock: Clock,
    ): ProfileOwnershipRuntime = ProfileOwnershipRuntime(journal, transport, clock)

    /** Explicit alias preserves the exact singleton used by the native transfer adapters. */
    @Provides
    internal fun ownershipAdmission(runtime: ProfileOwnershipRuntime): HarnessOwnershipRuntime = runtime

    @Provides
    @SingleIn(ProfileScope::class)
    internal fun github(
        transport: GitHubTransport,
        clock: Clock,
        origins: HarnessCallOrigins,
        toggles: FeatureToggles,
        decisions: GitHubProcessAuthorityFactory,
        imported: ProtectedImportedGitHub,
    ): HarnessScriptGitHubFactory = HarnessScriptGitHubFactory(transport, clock, origins, toggles, decisions, imported)

    @Provides
    @SingleIn(ProfileScope::class)
    internal fun commands(
        commands: LocalCommands,
        scopes: HarnessCreationScope,
        slots: HarnessCommandSlots,
        origins: HarnessCallOrigins,
        toggles: FeatureToggles,
        processes: HarnessProcessCommandHost,
    ): HarnessScriptCommandsFactory = HarnessScriptCommandsFactory(commands, scopes, slots, origins, toggles, processes)
}
