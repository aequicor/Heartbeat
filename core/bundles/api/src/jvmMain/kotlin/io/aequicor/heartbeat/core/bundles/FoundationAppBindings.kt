package io.aequicor.heartbeat.core.bundles

import dev.zacsweers.metro.AppScope
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.RecordRetentions
import io.aequicor.heartbeat.core.datastore.StorageRoot
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ScopeFactory
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggleControl
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.navigation.NavHostFactory
import io.aequicor.heartbeat.core.network.release.ReleaseSource
import io.aequicor.heartbeat.core.network.release.VerifiedArtifactCache
import io.aequicor.heartbeat.core.network.release.VerifiedReleaseDownloader
import io.aequicor.heartbeat.core.profilefacade.ProfileSessions
import io.aequicor.heartbeat.core.secrets.SecretStorageInfo
import io.aequicor.heartbeat.core.statemachine.MachineLauncher
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.ktor.client.HttpClient

/**
 * Stateful app-level bindings the foundation graph hands to every bundle graph (`@Includes`). Stateless
 * contributions of non-impl libraries (for example the default `DispatcherProvider`) are not listed: each bundle
 * graph compiles its own copy, and a second binding of the same type would clash.
 */
public interface FoundationAppBindings {
    /** Toggles of the whole application (bundle toggles are collected into it by the foundation). */
    public val featureToggles: FeatureToggles

    /** Verified downloads shared by every bundle. */
    public val verifiedArtifacts: VerifiedArtifactCache

    /** Scope of the application: coroutines and cleanup of services that live as long as the app. */
    @ForScope(AppScope::class)
    public val appScope: ScopeHandle

    /** Stores of the data that belongs to the whole application. */
    @ForScope(AppScope::class)
    public val dataStores: DataStores

    /** Root directory of the application's files. */
    public val storageRoot: StorageRoot

    /** Changes of toggle overrides (the flags panel). */
    public val featureToggleControl: FeatureToggleControl

    /** Sessions of the signed-in profiles (settings and welcome show what a profile allows). */
    public val profileSessions: ProfileSessions

    /** Latest release of the application (the update check). */
    public val releaseSource: ReleaseSource

    /** Downloads of release artifacts with checksum verification. */
    public val releaseDownloader: VerifiedReleaseDownloader

    /** Creates child scopes of retained screens and sessions. */
    public val scopeFactory: ScopeFactory

    /** Builds the navigation hosts (stacks, panels) of retained screens. */
    public val navHostFactory: NavHostFactory

    /** Launches state machines in the application's machine runtime. */
    public val machineLauncher: MachineLauncher

    /** Machines of the application's runtime, by key. */
    public val machineRegistry: MachineRegistry

    /** HTTP client of the application. */
    public val httpClient: HttpClient

    /** Where and how the secrets are kept (the connection wizard tells it to the user). */
    public val secretStorage: SecretStorageInfo

    /** Retention stamps of stored records (harness journals expire by them). */
    public val recordRetentions: RecordRetentions
}
