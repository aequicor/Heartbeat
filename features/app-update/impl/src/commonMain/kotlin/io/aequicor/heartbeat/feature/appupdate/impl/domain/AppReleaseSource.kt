package io.aequicor.heartbeat.feature.appupdate.impl.domain

import io.aequicor.heartbeat.feature.appupdate.api.AppRelease
import io.aequicor.heartbeat.feature.appupdate.api.AppVersion

/** Where the app's releases are published. */
internal fun interface AppReleaseSource {
    /**
     * The newest release of [repository] newer than [installed], with the build for [platform] and its SHA-256; null
     * when [installed] is the newest. Fails with [AppUpdateException] when the newer release cannot be verified.
     */
    suspend fun newer(repository: GitHubRepository, installed: AppVersion, platform: ReleasePlatform): AppRelease?
}
