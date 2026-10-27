package io.aequicor.heartbeat.feature.appupdate.api

import io.aequicor.heartbeat.core.navigation.Route
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The "Updates" settings page: the running build, its bundles and the check for a newer release. It needs no profile;
 * the settings host owns the embedded header.
 */
@Serializable
@SerialName("app-updates")
public data class AppUpdatesRoute(val isEmbedded: Boolean = true) : Route

/**
 * What the running desktop launch states about itself. [version] is null for a development launch. [train] is the
 * id of the bundle train the platform launcher started, null for a launch without a launcher; [isBeta] marks a train
 * published as a pre-release. [bundles] lists the bundles of that train and is empty when they are unknown.
 */
public data class AppBuildInfo(
    val version: String?,
    val train: String?,
    val isBeta: Boolean,
    val bundles: List<BundleBuild>,
)

/** One bundle of the running train with the version it was built as. */
public data class BundleBuild(val id: String, val version: String)

/** Reads [AppBuildInfo] of this launch; only desktop hosts have a source. */
public fun interface AppBuildInfoSource {
    /** The build of this launch; an unreadable train only leaves [AppBuildInfo.bundles] empty. */
    public suspend fun read(): AppBuildInfo
}
