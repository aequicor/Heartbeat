package io.aequicor.heartbeat.feature.appupdate.impl.data

import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateFailure
import io.aequicor.heartbeat.feature.appupdate.impl.domain.AppUpdateException
import io.aequicor.heartbeat.feature.appupdate.impl.domain.updateFailure
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Name of the release asset that makes a GitHub (pre-)release a bundle train. */
internal const val TRAIN_MANIFEST_ASSET = "train-manifest.json"

/**
 * The platform API this build of the app (the launcher and the shared platform code) implements. A train that needs a
 * higher one cannot run on it: the platform comes first, through a full installer.
 */
internal const val INSTALLED_PLATFORM_API = 2

/**
 * `train-manifest.json` of a train: the bundles of one desktop release. The field names are the contract with the
 * launcher (`platform-main/launcher`), which reads the same file. [bundles] lists every bundle of the train, changed
 * or not; a jar the train does not carry itself stays where an earlier release published it.
 */
@Serializable
internal data class TrainManifest(
    val train: String,
    val platformApi: Int,
    val dbSchema: Int? = null,
    val bundles: List<TrainBundle>,
)

/**
 * One bundle of a train: [jar] is a plain file name whose content has the lowercase hex [sha256]. [url] is the
 * absolute address of the jar in the release that first published it (`scripts/train_manifest.py`), null in a train
 * written before the field existed; it is only a hint where to download from, the digest decides what is accepted.
 *
 * [kind] is `bundle` (also when absent), `base` or `launcher`. The `launcher` entry is the jar of the platform
 * launcher: it is downloaded and verified like the others, but the launcher does not put it on the class path;
 * [launcherApi] is its monotonic level and [signature] the Ed25519 signature of the record, which the launcher
 * selector of the installed app
 * verifies (this app only checks the shape).
 */
@Serializable
internal data class TrainBundle(
    val id: String,
    val version: String,
    val jar: String,
    val sha256: String,
    val url: String? = null,
    val kind: String? = null,
    val launcherApi: Int? = null,
    val signature: String? = null,
)

/**
 * Parses and validates [text]. Anything the launcher would refuse is [AppUpdateFailure.InvalidRelease] here, so a
 * broken train is never downloaded: a train id that is not a plain name (it names a directory), a non-positive
 * `platformApi`, no bundles, duplicate bundle ids or jars, a jar name with a path separator or without `.jar`, and a
 * SHA-256 that is not 64 hex digits. Unknown fields are ignored; digests are normalised to lowercase. A bundle `url`
 * is kept as published: [missingDownloads] uses it only as a release download of the configured repository.
 */
internal fun parseTrainManifest(text: String): TrainManifest {
    val manifest = decode(text)
    if (!TrainId.matches(manifest.train)) invalid("train id")
    if (manifest.platformApi < 1) invalid("platformApi")
    if (manifest.dbSchema != null && manifest.dbSchema < 0) invalid("dbSchema")
    if (manifest.bundles.isEmpty()) invalid("no bundles")
    manifest.bundles.forEach(::validate)
    if (manifest.bundles.distinctBy { it.id }.size != manifest.bundles.size) invalid("duplicate bundle id")
    if (manifest.bundles.distinctBy { it.jar }.size != manifest.bundles.size) invalid("duplicate jar")
    if (manifest.bundles.count { it.kind == KIND_LAUNCHER } > 1) invalid("more than one launcher entry")
    return manifest.copy(bundles = manifest.bundles.map { it.copy(sha256 = it.sha256.lowercase()) })
}

/** Whether [id] is a valid train id: a plain name, because it names a directory of the launcher. */
internal fun isTrainId(id: String): Boolean = TrainId.matches(id)

private fun validate(bundle: TrainBundle) {
    if (bundle.id.isBlank() || bundle.version.isBlank()) invalid("bundle id or version")
    if (!isPlainJarName(bundle.jar)) invalid("jar name")
    if (!Sha256Hex.matches(bundle.sha256)) invalid("sha256")
    if (bundle.kind != null && bundle.kind !in KINDS) invalid("kind of ${bundle.id}")
    if (bundle.launcherApi != null && bundle.launcherApi < 1) invalid("launcherApi of ${bundle.id}")
    if (bundle.kind == KIND_LAUNCHER && bundle.launcherApi == null) invalid("launcherApi of the launcher entry")
    if (bundle.signature != null && !Signature.matches(bundle.signature)) invalid("signature of ${bundle.id}")
}

private fun decode(text: String): TrainManifest = try {
    ManifestJson.decodeFromString<TrainManifest>(text)
} catch (e: SerializationException) {
    throw AppUpdateException(AppUpdateFailure.InvalidRelease, "train manifest is not valid JSON", e)
} catch (e: IllegalArgumentException) {
    throw AppUpdateException(AppUpdateFailure.InvalidRelease, "train manifest is not valid JSON", e)
}

private fun isPlainJarName(name: String): Boolean =
    name.endsWith(".jar") && name.length > ".jar".length && name != ".." && name.none { it == '/' || it == '\\' } &&
        name.none { it.isISOControl() }

private fun invalid(what: String): Nothing = updateFailure(AppUpdateFailure.InvalidRelease, "train manifest: $what")

private val ManifestJson = Json { ignoreUnknownKeys = true }
private const val KIND_LAUNCHER = "launcher"
private val KINDS = setOf("bundle", "base", KIND_LAUNCHER)

/** Ed25519 signature: 64 bytes in Base64. */
private val Signature = Regex("[A-Za-z0-9+/]{86}==")
private val TrainId = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
private val Sha256Hex = Regex("[0-9a-fA-F]{64}")
