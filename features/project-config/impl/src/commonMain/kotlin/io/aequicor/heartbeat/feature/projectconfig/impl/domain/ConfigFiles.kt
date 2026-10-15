package io.aequicor.heartbeat.feature.projectconfig.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigFile
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigLayout
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigLimits
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigPath
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigProblem
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigProblemCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Files of a whole tree, sorted by path, without the manifest; [ignored] are files found but not read (hidden files
 * are skipped silently, everything else is reported). [toString] never shows texts.
 */
internal data class ConfigTree(val files: List<ConfigFile>, val ignored: List<ConfigProblem> = emptyList()) {
    init {
        require(files.none { it.path.value == ConfigLayout.MANIFEST }) { "The manifest is not a tree file" }
    }

    override fun toString(): String = "ConfigTree(${files.size} files, ${ignored.size} ignored)"

    companion object {
        /** Tree of [files] in canonical order; a manifest among them is dropped. */
        fun of(files: Collection<ConfigFile>, ignored: List<ConfigProblem> = emptyList()): ConfigTree = ConfigTree(
            files.filterNot { it.path.value == ConfigLayout.MANIFEST }.sortedBy { it.path.value },
            ignored,
        )
    }
}

/** Outcome of reading a project folder or bundle. */
internal sealed interface ConfigRead {
    /** The tree was read. */
    data class Read(val tree: ConfigTree) : ConfigRead

    /** A project has no `.heartbeat` folder. */
    data object Absent : ConfigRead

    /** Nothing was read. */
    data class Failed(val failure: ConfigReadFailure) : ConfigRead
}

/** Why a tree was not read; never carries paths or texts. */
internal enum class ConfigReadFailure {
    /** The platform cannot read configuration trees. */
    Unsupported,

    /** The project folder or bundle does not exist or cannot be opened. */
    Unavailable,

    /** `.heartbeat` is a symbolic link or not a directory; it is never followed. */
    NotDirectory,

    /** The tree exceeds [ConfigLimits]. */
    Limit,

    /** The bundle has no valid manifest, duplicates or entries outside the tree. */
    Format,

    /** The manifest declares a newer version than [ConfigLayout.VERSION]. */
    Version,

    /** Reading failed. */
    Io,
}

/** A write was refused or failed; nothing beyond the files already replaced was changed. */
internal class ConfigWriteException(val reason: ConfigWriteFailure, cause: Throwable? = null) :
    IllegalStateException(reason.name, cause)

/** Why a write did not complete. */
internal enum class ConfigWriteFailure { Unsupported, Unavailable, NotDirectory, Limit, Io }

/** The `.heartbeat` folders of project roots (absolute directories resolved through `LocalWorkspaces`). */
internal interface ProjectFolders {
    /** Whether this platform reads and writes project folders. */
    val isAvailable: Boolean

    /** Reads `<root>/.heartbeat`; [ConfigRead.Absent] when it does not exist. Never follows symbolic links. */
    suspend fun read(root: String): ConfigRead

    /**
     * Cheap marker of the folder state (paths, sizes and modification times): equal markers mean nothing to read
     * again; null when the folder does not exist or cannot be listed.
     */
    suspend fun fingerprint(root: String): String?

    /**
     * Replaces [put] files and deletes [delete] files in `<root>/.heartbeat`, creating it with a manifest when
     * absent and removing directories left empty. Each file is replaced atomically.
     * @throws ConfigWriteException when the folder cannot be written.
     */
    suspend fun write(root: String, put: List<ConfigFile>, delete: List<ConfigPath>)
}

/** `.hbai` bundles: a ZIP of the tree with its manifest. */
internal interface ConfigBundles {
    /** Whether this platform reads and writes bundles. */
    val isAvailable: Boolean

    /** Reads bundle [path]; the manifest is required. */
    suspend fun read(path: String): ConfigRead

    /**
     * Writes [tree] with a manifest to [path], replacing it atomically.
     * @throws ConfigWriteException when the bundle cannot be written.
     */
    suspend fun write(path: String, tree: ConfigTree)
}

/** Manifest file at the root of a tree. */
@Serializable
internal data class ConfigManifest(
    @SerialName("format") val kind: String = ConfigLayout.FORMAT,
    @SerialName("version") val version: Int = ConfigLayout.VERSION,
)

/** Manifest codec and the text rules shared by every container. */
internal object ConfigManifests {
    private val log = Log.tag("ConfigManifests")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    /** The manifest written into every new folder and bundle. */
    val current: ConfigFile = ConfigFile(
        ConfigPath(ConfigLayout.MANIFEST),
        json.encodeToString(ConfigManifest.serializer(), ConfigManifest()) + "\n",
    )

    /** Null when [text] is a manifest this version reads, otherwise why not. */
    fun check(text: String): ConfigReadFailure? {
        val manifest = decode(text)
        return when {
            manifest == null || manifest.kind != ConfigLayout.FORMAT || manifest.version < 1 -> ConfigReadFailure.Format
            manifest.version > ConfigLayout.VERSION -> ConfigReadFailure.Version
            else -> null
        }
    }

    private fun decode(text: String): ConfigManifest? = try {
        json.decodeFromString(ConfigManifest.serializer(), text)
    } catch (e: SerializationException) {
        log.w(e) { "Unreadable configuration manifest" }
        null
    } catch (e: IllegalArgumentException) {
        log.w(e) { "Invalid configuration manifest" }
        null
    }

    /**
     * Text of a file as every container stores it: without a byte order mark and with `\n` line ends, so a checkout
     * that converts line ends does not look like a change.
     */
    fun normalized(text: String): String = text.removePrefix(BOM).replace("\r\n", "\n")

    /** Problem of a file that is not part of the tree. */
    fun ignored(path: String, code: ConfigProblemCode): ConfigProblem =
        ConfigProblem(code, ConfigPath.parse(path), if (ConfigPath.isValid(path)) "" else path.take(DETAIL_CHARS))

    private const val BOM = "﻿"
    private const val DETAIL_CHARS = 120
}

/** Short outcome for logs: a kind and failure name, never paths or texts. */
internal val ConfigRead.outcome: String
    get() = when (this) {
        is ConfigRead.Read -> "read ${tree.files.size} files, ${tree.ignored.size} ignored"
        ConfigRead.Absent -> "absent"
        is ConfigRead.Failed -> "failed $failure"
    }
