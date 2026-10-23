package io.aequicor.heartbeat.feature.appupdate.impl.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.core.datastore.StorageRoot
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.MachineLauncher
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateBetaChannel
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateEnabled
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateIntent
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateMachineSpec
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateOutput
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateState
import io.aequicor.heartbeat.feature.appupdate.impl.data.AppReleases
import io.aequicor.heartbeat.feature.appupdate.impl.data.CommandRunner
import io.aequicor.heartbeat.feature.appupdate.impl.data.DesktopInstallationProbe
import io.aequicor.heartbeat.feature.appupdate.impl.data.DesktopUpdateInstaller
import io.aequicor.heartbeat.feature.appupdate.impl.data.GitHubReleaseClient
import io.aequicor.heartbeat.feature.appupdate.impl.data.LaunchProperties
import io.aequicor.heartbeat.feature.appupdate.impl.data.MacBundleUpdate
import io.aequicor.heartbeat.feature.appupdate.impl.data.TrainUpdate
import io.aequicor.heartbeat.feature.appupdate.impl.data.UpdateDownloader
import io.aequicor.heartbeat.feature.appupdate.impl.data.UpdateWorkspace
import io.aequicor.heartbeat.feature.appupdate.impl.data.updatesRoot
import io.aequicor.heartbeat.feature.appupdate.impl.domain.AppUpdateEffects
import io.aequicor.heartbeat.feature.appupdate.impl.domain.UpdateChannel

/**
 * The update machine lives for the whole desktop launch (app scope). Only the desktop graph has it: mobile apps are
 * updated by their stores.
 */
@ContributesTo(AppScope::class)
@BindingContainer
public object AppUpdateBindings {
    @Provides
    @SingleIn(AppScope::class)
    internal fun machine(
        launcher: MachineLauncher,
        @ForScope(AppScope::class) scope: ScopeHandle,
        effects: AppUpdateEffects,
    ): Machine<AppUpdateState, AppUpdateIntent, AppUpdateOutput> = launcher.launch(AppUpdateMachineSpec, scope, effects)

    @Provides
    internal fun effects(
        probe: DesktopInstallationProbe,
        releases: AppReleases,
        installer: DesktopUpdateInstaller,
        toggles: FeatureToggles,
    ): AppUpdateEffects = AppUpdateEffects(probe, releases, installer) {
        if (toggles.get(AppUpdateBetaChannel)) UpdateChannel.Beta else UpdateChannel.Stable
    }

    /** What the jpackage launcher states about this launch. */
    @Provides
    internal fun launch(): LaunchProperties = LaunchProperties()

    /** One installer per launch: it reads the outcome of an earlier attempt once. */
    @Provides
    @SingleIn(AppScope::class)
    internal fun installer(
        platform: PlatformInfo,
        storage: StorageRoot,
        downloader: UpdateDownloader,
        mac: MacBundleUpdate,
        client: GitHubReleaseClient,
        commands: CommandRunner,
        launch: LaunchProperties,
        dispatchers: DispatcherProvider,
    ): DesktopUpdateInstaller = DesktopUpdateInstaller(
        workspace = UpdateWorkspace(updatesRoot(platform.host, storage)),
        downloader = downloader,
        mac = mac,
        trains = TrainUpdate(client, downloader, dispatchers.io),
        launch = launch,
        io = dispatchers.io,
        startHelper = commands::startDetached,
    )
}

/** Registers the update toggle in the desktop controls. */
@ContributesTo(AppScope::class)
@BindingContainer
public object AppUpdateToggleBindings {
    /** The exact result type joins the feature-toggle registry. */
    @Provides
    @IntoSet
    public fun appUpdate(): FeatureToggle<*> = AppUpdateEnabled

    /** The beta update channel switch. */
    @Provides
    @IntoSet
    public fun appUpdateBeta(): FeatureToggle<*> = AppUpdateBetaChannel
}
