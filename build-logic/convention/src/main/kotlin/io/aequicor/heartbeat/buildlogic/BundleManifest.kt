package io.aequicor.heartbeat.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.security.MessageDigest

/** Bundles every other bundle builds on; each bundle of the train lists them in its manifest entry. */
internal val BUNDLE_BASE_DEPENDENCIES = listOf("contracts", "foundation")

/**
 * What an entry of the train is: a feature bundle, a base artifact (`foundation`, `contracts`) the bundles build on,
 * or the `launcher` jar, which the launcher selector installs into a slot and which is not on the application class path.
 */
internal enum class BundleArtifactKind(val json: String) { Bundle("bundle"), Base("base"), Launcher("launcher") }

/**
 * One artifact of the train as the launcher reads it. [launcherApi] is the monotonic level of a launcher entry
 * (0 for the others) and [signature] its Ed25519 signature in Base64 (null: not signed).
 */
internal data class BundleManifestEntry(
    val id: String,
    val version: String,
    val jar: String,
    val sha256: String,
    val dependencies: List<String>,
    val kind: BundleArtifactKind = BundleArtifactKind.Bundle,
    val launcherApi: Int = 0,
    val signature: String? = null,
)

/** Base artifacts and what each depends on: `contracts` (the `features:*:api`) is built on the foundation. */
internal val BASE_ARTIFACT_DEPENDENCIES = mapOf(
    "foundation" to emptyList(),
    "contracts" to listOf("foundation"),
    LAUNCHER_ID to emptyList(),
)

/** Id of the launcher entry of the train. */
internal const val LAUNCHER_ID = "launcher"

/** File name of the bundle jar: stable for a given id and version, so the manifest can point at it. */
internal fun bundleJarName(id: String, version: String): String = "bundle-$id-$version.jar"

internal fun sha256Hex(file: File): String = file.inputStream().use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(BUFFER_SIZE)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
    }
    digest.digest().toHex()
}

internal fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/**
 * Train manifest as JSON. Bundles are sorted by id and no timestamps are written, so equal inputs give equal bytes.
 */
internal fun renderBundleManifest(platformApi: Int, entries: List<BundleManifestEntry>): String = buildString {
    appendLine("{")
    appendLine("  \"platformApi\": $platformApi,")
    appendLine("  \"bundles\": [")
    val sorted = entries.sortedBy { it.id }
    sorted.forEachIndexed { index, entry ->
        appendLine("    {")
        appendLine("      \"id\": ${jsonString(entry.id)},")
        appendLine("      \"version\": ${jsonString(entry.version)},")
        appendLine("      \"jar\": ${jsonString(entry.jar)},")
        appendLine("      \"sha256\": ${jsonString(entry.sha256)},")
        // only base artifacts and the launcher carry a kind, so plain bundles keep the format launchers already read
        if (entry.kind != BundleArtifactKind.Bundle) appendLine("      \"kind\": ${jsonString(entry.kind.json)},")
        if (entry.launcherApi > 0) appendLine("      \"launcherApi\": ${entry.launcherApi},")
        entry.signature?.let { appendLine("      \"signature\": ${jsonString(it)},") }
        appendLine("      \"dependencies\": [${entry.dependencies.sorted().joinToString(", ") { jsonString(it) }}]")
        appendLine("    }" + if (index < sorted.lastIndex) "," else "")
    }
    appendLine("  ]")
    appendLine("}")
}

private fun jsonString(value: String): String = buildString {
    append('"')
    for (char in value) {
        when {
            char == '"' -> append("\\\"")
            char == '\\' -> append("\\\\")
            char < ' ' -> append("\\u%04x".format(char.code))
            else -> append(char)
        }
    }
    append('"')
}

/**
 * Root task `bundleManifest`: writes the JSON manifest of the train for every `:bundles:*` project —
 * bundle id, version, jar name, SHA-256 of the jar and the base bundles it depends on — together with `platformApi`.
 * The base artifacts (`foundation`, `contracts`) are listed with `kind=base`; the `launcher` entry has `kind=launcher`,
 * its monotonic level `launcherApi` and, when a signing key is given, the Ed25519 `signature` of its record.
 */
abstract class BundleManifest : DefaultTask() {
    /** `bundle id -> version`, filled by each `heartbeat.bundle` project. */
    @get:Input
    abstract val versions: MapProperty<String, String>

    /**
     * `artifact id -> version` of the prebuilt `foundation`, `contracts` and `launcher` jars of the train (same jar
     * naming as the bundles). The `launcher` entry gets `kind`, [launcherApi] and a signature. Empty keeps the
     * manifest to the bundles.
     */
    @get:Input
    abstract val baseArtifacts: MapProperty<String, String>

    /** The platform API level the train is built for; the launcher refuses a train above its own level. */
    @get:Input
    abstract val platformApi: Property<Int>

    /** Monotonic level of the launcher in the train: a launcher below the highest confirmed level is not accepted. */
    @get:Input
    @get:Optional
    abstract val launcherApi: Property<Int>

    /**
     * PKCS#8 Ed25519 private key (Base64) that signs the launcher entry. A secret: it is not an input of the task, so it
     * never reaches the build cache key or a log. Without it the launcher entry is written unsigned, and the selector of an
     * installed app does not accept such a launcher.
     */
    @get:Internal
    abstract val launcherSigningKey: Property<String>

    /**
     * SHA-256 of [launcherSigningKey] without whitespace (the way signing reads it), the only trace of the secret among
     * the inputs: a manifest written without the key (or with another one) is not up to date once the key is given, so a
     * signed train is never skipped as unchanged. Absent for a missing or blank key. Derived here, not wired by the
     * caller, so no way of setting the key can forget it.
     */
    @get:Input
    @get:Optional
    val launcherSigningKeyDigest: Provider<String>
        get() = launcherSigningKey.map { key -> key.filterNot(Char::isWhitespace) }.filter { it.isNotEmpty() }
            .map { key -> sha256Hex(key.toByteArray()) }

    /** Jars made by `assembleBundle` of every bundle. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val jars: ConfigurableFileCollection

    @get:OutputFile
    abstract val manifestFile: RegularFileProperty

    private fun launcherEntry(version: String, jar: String, sha256: String): BundleManifestEntry {
        val level = launcherApi.orNull?.takeIf { it > 0 }
            ?: throw GradleException("The launcher level launcherApi must be set to a positive number")
        val kind = BundleArtifactKind.Launcher
        val signature = launcherSigningKey.orNull?.takeIf { it.isNotBlank() }?.let { key ->
            signEd25519(key, launcherRecordCanonical(LAUNCHER_ID, version, sha256, kind.json, level))
        }
        return BundleManifestEntry(LAUNCHER_ID, version, jar, sha256, emptyList(), kind, level, signature)
    }

    @TaskAction
    fun write() {
        val jarsByName = jars.files.associateBy { it.name }
        val base = baseArtifacts.get().map { (id, version) ->
            val dependencies = BASE_ARTIFACT_DEPENDENCIES[id]
                ?: throw GradleException("Unknown base artifact $id, expected ${BASE_ARTIFACT_DEPENDENCIES.keys}")
            val name = bundleJarName(id, version)
            val jar = jarsByName[name] ?: throw GradleException("Base artifact jar $name is missing")
            val sha = sha256Hex(jar)
            if (id == LAUNCHER_ID) launcherEntry(version, name, sha) else {
                BundleManifestEntry(id, version, name, sha, dependencies, BundleArtifactKind.Base)
            }
        }
        val entries = base + versions.get().map { (id, version) ->
            val name = bundleJarName(id, version)
            val jar = jarsByName[name] ?: throw GradleException("Bundle jar $name is missing: run assembleBundle")
            BundleManifestEntry(
                id = id,
                version = version,
                jar = name,
                sha256 = sha256Hex(jar),
                dependencies = BUNDLE_BASE_DEPENDENCIES.filter { it != id },
            )
        }
        manifestFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(renderBundleManifest(platformApi.get(), entries))
        }
    }
}

private const val BUFFER_SIZE = 8192
