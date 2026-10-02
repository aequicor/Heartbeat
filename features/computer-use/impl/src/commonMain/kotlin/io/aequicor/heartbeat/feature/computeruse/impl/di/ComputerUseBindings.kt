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
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseEffect
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseEnabled
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseIntent
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseMachineKey
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseMachineSpec
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseNativeRouting
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseOutput
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseState
import io.aequicor.heartbeat.feature.computeruse.api.VisionBudget
import io.aequicor.heartbeat.feature.computeruse.impl.data.CaptureCoordinator
import io.aequicor.heartbeat.feature.computeruse.impl.data.FramePipeline
import io.aequicor.heartbeat.feature.computeruse.impl.data.MasterFrameCache
import io.aequicor.heartbeat.feature.computeruse.impl.domain.ComputerUsePreferences
import io.aequicor.heartbeat.feature.computeruse.impl.domain.FrameEncoder
import io.aequicor.heartbeat.feature.computeruse.impl.domain.FrameStore
import io.aequicor.heartbeat.feature.computeruse.impl.domain.InputInjector
import io.aequicor.heartbeat.feature.computeruse.impl.domain.ScreenCapturer
import io.aequicor.heartbeat.feature.computeruse.impl.domain.WindowCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Profile-owned computer use graph.
 *
 * The machine lives in the profile scope, not in a screen scope: an agent turn keeps capturing after the settings
 * screen is closed, and the kill switch stays reachable through [ComputerUseMachineKey] at any time.
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
        resources: ComputerUseCoordinatorResources,
        @ForScope(ProfileScope::class) profile: ScopeHandle,
        @ForScope(AppScope::class) app: ScopeHandle,
    ): CaptureCoordinator = resources.create().also { coordinator ->
        profile.onClose {
            // Start even during app shutdown; closeAndPurge shields its finite cleanup from cancellation.
            app.coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    coordinator.closeAndPurge()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.tag("ComputerUseCleanup").w(e) { "profile capture cleanup failed" }
                }
            }
        }
    }

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

/** Dependencies of the profile coordinator, grouped so the scoped provider only manages its lifetime. */
@Inject
internal class ComputerUseCoordinatorResources(
    private val capturer: ScreenCapturer,
    private val windows: WindowCatalog,
    private val injector: InputInjector,
    private val pipeline: FramePipeline,
    private val cache: MasterFrameCache,
    private val dispatchers: DispatcherProvider,
) {
    /** Creates the one profile-owned coordinator. */
    fun create(): CaptureCoordinator = CaptureCoordinator(capturer, windows, injector, pipeline, cache, dispatchers)
}

/** Observes profile opt in and rollout availability, releasing capture even while the settings screen is closed. */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class ComputerUseStartup(
    private val machine: Lazy<Machine<ComputerUseState, ComputerUseIntent, ComputerUseOutput>>,
    private val toggles: FeatureToggles,
    private val preferences: Lazy<ComputerUsePreferences>,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
) : ProfileStartup {
    private val log = Log.tag("ComputerUseStartup")

    override fun start() {
        profile.coroutineScope.launch {
            toggles.observe(ComputerUseEnabled).distinctUntilChanged().collectLatest { isAvailable ->
                if (isAvailable) {
                    preferences.value.observe().map { it.isEnabled }.distinctUntilChanged().collect(::applyEnabled)
                } else {
                    applyEnabled(false)
                }
            }
        }
    }

    private suspend fun applyEnabled(isEnabled: Boolean) {
        if (isEnabled) {
            val intent = when (machine.value.state.value) {
                is ComputerUseState.Unavailable, is ComputerUseState.Failed -> ComputerUseIntent.Public.Retry
                ComputerUseState.Idle -> ComputerUseIntent.Public.Start
                ComputerUseState.Checking, is ComputerUseState.Ready, is ComputerUseState.Capturing -> return
            }
            val result = machine.value.send(intent)
            log.i { "computer use startup result=$result" }
        } else if (machine.isInitialized()) {
            val result = machine.value.send(ComputerUseIntent.Public.Revoke)
            log.i { "computer use disabled and revoked result=$result" }
        } else {
            log.i { "computer use is disabled" }
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
    public fun nativeRouting(): FeatureToggle<*> = ComputerUseNativeRouting
}
