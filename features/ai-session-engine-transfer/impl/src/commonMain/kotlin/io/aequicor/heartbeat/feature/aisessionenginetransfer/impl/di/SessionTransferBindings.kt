package io.aequicor.heartbeat.feature.aisessionenginetransfer.impl.di

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
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.MachineLauncher
import io.aequicor.heartbeat.feature.aisessionenginetransfer.api.SessionEngineTransfer
import io.aequicor.heartbeat.feature.aisessionenginetransfer.api.SessionTransferIntent
import io.aequicor.heartbeat.feature.aisessionenginetransfer.api.SessionTransferMachineSpec
import io.aequicor.heartbeat.feature.aisessionenginetransfer.api.SessionTransferOutput
import io.aequicor.heartbeat.feature.aisessionenginetransfer.api.SessionTransferState
import io.aequicor.heartbeat.feature.aisessionenginetransfer.impl.domain.ConversationJournal
import io.aequicor.heartbeat.feature.aisessionenginetransfer.impl.domain.EngineSessions
import io.aequicor.heartbeat.feature.aisessionenginetransfer.impl.domain.SessionTranscripts
import io.aequicor.heartbeat.feature.aisessionenginetransfer.impl.domain.SessionTransferEffects
import io.aequicor.heartbeat.feature.aisessionenginetransfer.impl.domain.TransferGate
import io.aequicor.heartbeat.feature.aisessionenginetransfer.impl.domain.TransferStamps

/**
 * The transfer machine is owned by the profile, so a transfer outlives the screen that started it.
 * It is created on first injection; until then `MachineRegistry.send` reports `NotRunning`.
 */
@ContributesTo(ProfileScope::class)
@BindingContainer
object SessionTransferBindings {
    @Provides
    internal fun effects(
        gate: TransferGate,
        transcripts: SessionTranscripts,
        sessions: EngineSessions,
        journal: ConversationJournal,
        stamps: TransferStamps,
    ): SessionTransferEffects = SessionTransferEffects(gate, transcripts, sessions, journal, stamps)

    @Provides
    @SingleIn(ProfileScope::class)
    internal fun machine(
        launcher: MachineLauncher,
        @ForScope(ProfileScope::class) scope: ScopeHandle,
        effects: SessionTransferEffects,
    ): Machine<SessionTransferState, SessionTransferIntent, SessionTransferOutput> =
        launcher.launch(SessionTransferMachineSpec, scope, effects)
}

/** Registers the transfer toggle in the toggles panel. */
@ContributesTo(AppScope::class)
@BindingContainer
object SessionTransferToggleBindings {
    /** The type must be exactly `FeatureToggle<*>` to join the registry set. */
    @Provides
    @IntoSet
    fun sessionTransfer(): FeatureToggle<*> = SessionEngineTransfer
}
