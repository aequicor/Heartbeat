package io.aequicor.heartbeat.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.security.MessageDigest

/** Bundles every other bundle builds on; each bundle of the train lists them in its manifest entry. */
internal val BUNDLE_BASE_DEPENDENCIES = listOf("contracts", "foundation")

/** What an entry of the train is: a feature bundle or a base artifact (`foundation`, `contracts`) the bundles build on. */
internal enum class BundleArtifactKind(val json: String) { Bundle("bundle"), Base("base") }

/** One artifact of the train as the launcher reads it. */
internal data class BundleManifestEntry(
    val id: String,
    val version: String,
    val jar: String,
    val sha256: String,
    val dependencies: List<String>,
    val kind: BundleArtifactKind = BundleArtifactKind.Bundle,
)

/** Base artifacts and what each depends on: `contracts` (the `features:*:api`) is built on the foundation. */
internal val BASE_ARTIFACT_DEPENDENCIES = mapOf(
    "foundation" to emptyList(),
    "contracts" to listOf("foundation"),
)

/** File name of the bundle jar: stable for a given id and version, so the manifest can point at it. */
internal fun bundleJarName(id: String, version: String): String = "bundle-$id-$version.jar"

internal fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

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
        // only base artifacts carry a kind, so the entries of plain bundles keep the format launchers already read
        if (entry.kind != BundleArtifactKind.Bundle) appendLine("      \"kind\": ${jsonString(entry.kind.json)},")
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
 */
abstract class BundleManifest : DefaultTask() {
    /** `bundle id -> version`, filled by each `heartbeat.bundle` project. */
    @get:Input
    abstract val versions: MapProperty<String, String>

    /**
     * `base artifact id -> version` of the prebuilt `foundation` and `contracts` jars of the train (same jar naming as
     * the bundles). Empty keeps the manifest to the bundles.
     */
    @get:Input
    abstract val baseArtifacts: MapProperty<String, String>

    /** The platform API level the train is built for; the launcher refuses a train above its own level. */
    @get:Input
    abstract val platformApi: Property<Int>

    /** Jars made by `assembleBundle` of every bundle. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val jars: ConfigurableFileCollection

    @get:OutputFile
    abstract val manifestFile: RegularFileProperty

    @TaskAction
    fun write() {
        val jarsByName = jars.files.associateBy { it.name }
        val base = baseArtifacts.get().map { (id, version) ->
            val dependencies = BASE_ARTIFACT_DEPENDENCIES[id]
                ?: throw GradleException("Unknown base artifact $id, expected ${BASE_ARTIFACT_DEPENDENCIES.keys}")
            val name = bundleJarName(id, version)
            val jar = jarsByName[name] ?: throw GradleException("Base artifact jar $name is missing")
            BundleManifestEntry(id, version, name, sha256Hex(jar), dependencies, BundleArtifactKind.Base)
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
