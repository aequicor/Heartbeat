package io.aequicor.heartbeat.feature.appupdate.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.appupdate.api.AppBuildInfo
import io.aequicor.heartbeat.feature.appupdate.api.AppBuildInfoSource
import io.aequicor.heartbeat.feature.appupdate.api.BundleBuild
import io.aequicor.heartbeat.feature.appupdate.impl.domain.AppUpdateException
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * The build of this desktop launch: the version and train the launcher states, and the bundles of that train read from
 * the manifest the launcher keeps at `<root>/trains/<train>/manifest.json`. An unreadable manifest only leaves the
 * bundle list empty.
 */
@Inject
internal class DesktopAppBuildInfoSource(
    private val launch: LaunchProperties,
    private val dispatchers: DispatcherProvider,
) : AppBuildInfoSource {
    private val log = Log.tag("AppBuildInfo")

    override suspend fun read(): AppBuildInfo {
        val train = launch.trainId
        val root = launch.launcherRoot
        val manifest = if (train != null && root != null && isTrainId(train)) {
            withContext(dispatchers.io) { manifest(root, train) }
        } else {
            null
        }
        return AppBuildInfo(
            version = launch.version?.toString(),
            train = train,
            isBeta = manifest?.isBeta == true,
            bundles = manifest?.bundles.orEmpty().map { BundleBuild(it.id, it.version) },
        )
    }

    private fun manifest(root: Path, train: String): RunningTrain? = try {
        val text = Files.readString(root.resolve(TRAINS).resolve(train).resolve(MANIFEST))
        RunningTrain(parseTrainManifest(text).bundles, isBeta = channelOf(text) == TRAIN_CHANNEL_BETA)
    } catch (e: IOException) {
        // The exception text names the local path; only its class is logged.
        log.w(IllegalStateException(e::class.simpleName)) { "train manifest of the running train is unreadable" }
        null
    } catch (e: AppUpdateException) {
        log.w(e) { "train manifest of the running train is invalid" }
        null
    }

    /** The bundles of the running train and whether the launcher marked it as a beta train. */
    private class RunningTrain(val bundles: List<TrainBundle>, val isBeta: Boolean)

    /** The `channel` the launcher keeps in the queued manifest; [parseTrainManifest] has validated the text already. */
    private fun channelOf(text: String): String? =
        (Json.parseToJsonElement(text).jsonObject[TRAIN_CHANNEL] as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val TRAINS = "trains"
        const val MANIFEST = "manifest.json"
    }
}
