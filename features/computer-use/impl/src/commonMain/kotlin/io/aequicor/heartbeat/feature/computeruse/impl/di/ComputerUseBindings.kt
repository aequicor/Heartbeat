package io.aequicor.heartbeat.feature.computeruse.impl.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.profilefacade.ProfileStartup
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.MachineLauncher
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseAgentTools
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseDesktopInput
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseEffect
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseEnabled
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseIntent
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseMachineSpec
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseNativeRouting
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseOutput
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseState
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseWindowMode
import io.aequicor.heartbeat.feature.computeruse.api.VisionBudget
import io.aequicor.heartbeat.feature.computeruse.impl.data.CaptureCoordinator
import io.aequicor.heartbeat.feature.computeruse.impl.data.FramePipeline
import io.aequicor.heartbeat.feature.computeruse.impl.data.MasterFrameCache
import io.aequicor.heartbeat.feature.computeruse.impl.domain.FrameEncoder
import io.aequicor.heartbeat.feature.computeruse.impl.domain.FrameStore
import io.aequicor.heartbeat.feature.computeruse.impl.domain.InputInjector
import io.aequicor.heartbeat.feature.computeruse.impl.domain.ScreenCapturer
import io.aequicor.heartbeat.feature.computeruse.impl.domain.WindowCatalog
import kotlinx.coroutines.launch

/**
 * Profile-owned computer use graph.
 *
 * The machine lives in the profile scope, not in a screen scope: an agent turn keeps capturing after the panel
 * is closed, and the kill switch stays reachable through [ComputerUseMachineKey] at any time.
 */
@ContributesTo(ProfileScope::class)
@BindingContainer
public object ComputerUseBindings {
    /** Token price the host promises for one frame sent to a model. */
    @Provides
    @SingleIn(ProfileScope::class)
    internal fun budget(): VisionBudget = VisionBudget(DEFAULT_TOKEN_BUDGET)

    /** Decoded master frames kept for crops; evicted by total size, purged with the session. */
    @Provides
    @SingleIn(ProfileScope::class)
    internal fun cache(encoder: FrameEncoder, store: FrameStore): MasterFrameCache =
        MasterFrameCache(encoder, store, MAX_CACHE_BYTES)

    /** Reduction ladder, encoding and storage of every frame. */
    @Provides
    @SingleIn(ProfileScope::class)
    internal fun pipeline(
        encoder: FrameEncoder,
        store: FrameStore,
        budget: VisionBudget,
        dispatchers: DispatcherProvider,
    ): FramePipeline = FramePipeline(encoder, store, budget, dispatchers)

    /** The one active capture of the profile. */
    @Provides
    @SingleIn(ProfileScope::class)
    internal fun coordinator(
        capturer: ScreenCapturer,
        windows: WindowCatalog,
        injector: InputInjector,
        pipeline: FramePipeline,
        cache: MasterFrameCache,
        dispatchers: DispatcherProvider,
    ): CaptureCoordinator = CaptureCoordinator(capturer, windows, injector, pipeline, cache, dispatchers)

    /** Launches the machine for the lifetime of the profile. */
    @Provides
    @SingleIn(ProfileScope::class)
    internal fun machine(
        launcher: MachineLauncher,
        @ForScope(ProfileScope::class) scope: ScopeHandle,
        effects: EffectHandler<ComputerUseEffect, ComputerUseIntent>,
    ): Machine<ComputerUseState, ComputerUseIntent, ComputerUseOutput> =
        launcher.launch(ComputerUseMachineSpec, scope, effects)

    private const val DEFAULT_TOKEN_BUDGET = 1200
    private const val MAX_CACHE_BYTES = 64L * 1024 * 1024
}

/** Probes availability with the profile, so the panel and the tools never wait for the first permission check. */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class ComputerUseStartup(
    private val machine: Lazy<Machine<ComputerUseState, ComputerUseIntent, ComputerUseOutput>>,
    private val toggles: FeatureToggles,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
) : ProfileStartup {
    private val log = Log.tag("ComputerUseStartup")

    override fun start() {
        profile.coroutineScope.launch {
            if (!toggles.get(ComputerUseEnabled)) {
                log.i { "computer use is disabled by toggle" }
                return@launch
            }
            val result = machine.value.send(ComputerUseIntent.Public.Start)
            log.i { "computer use startup result=$result" }
        }
    }
}

/** Registers the disabled-by-default computer use switches. */
@ContributesTo(AppScope::class)
@BindingContainer
public object ComputerUseToggleBindings {
    /** Exact FeatureToggle wildcard joins the application registry. */
    @Provides
    @IntoSet
    public fun enabled(): FeatureToggle<*> = ComputerUseEnabled

    /** Exact FeatureToggle wildcard joins the application registry. */
    @Provides
    @IntoSet
    public fun windowMode(): FeatureToggle<*> = ComputerUseWindowMode

    /** Exact FeatureToggle wildcard joins the application registry. */
    @Provides
    @IntoSet
    public fun desktopInput(): FeatureToggle<*> = ComputerUseDesktopInput

    /** Exact FeatureToggle wildcard joins the application registry. */
    @Provides
    @IntoSet
    public fun agentTools(): FeatureToggle<*> = ComputerUseAgentTools

    /** Exact FeatureToggle wildcard joins the application registry. */
    @Provides
    @IntoSet
    public fun nativeRouting(): FeatureToggle<*> = ComputerUseNativeRouting
}
