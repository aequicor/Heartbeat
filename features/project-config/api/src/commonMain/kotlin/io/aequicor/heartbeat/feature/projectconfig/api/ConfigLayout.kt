package io.aequicor.heartbeat.feature.projectconfig.api

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * Project configuration: the `.heartbeat` folder of a project holds harnesses (skills, instructions, templates,
 * script tools, workflows, tool policy), their interfaces and learned instructions, read while the project is used
 * and written back when they change in the app; the same tree packed into a `.hbai` bundle moves configuration
 * between profiles and machines. Desktop only. While off, nothing is read, written, reviewed or proposed.
 */
public val ProjectConfigEnabled: FeatureToggle.Flag = FeatureToggle.Flag(
    "project_config.enabled",
    "Конфигурация проекта: папка .heartbeat, доверие к её изменениям и перенос в файл .hbai",
)

/**
 * Tree shared by the `.heartbeat` folder of a project and the `.hbai` bundle, which is a ZIP of the same files.
 *
 * ```
 * manifest.json                       format and version (required in a bundle, optional in a folder)
 * <section>/…                         files of one section, grouped into units by the section (ConfigSection)
 * ```
 * Every file is UTF-8 text. Unknown sections and files a section does not claim are reported, never applied.
 */
public object ConfigLayout {
    /** Directory of the configuration inside a project root. */
    public const val PROJECT_DIRECTORY: String = ".heartbeat"

    /** File extension of a bundle, without the dot. */
    public const val BUNDLE_EXTENSION: String = "hbai"

    /** Manifest at the root of the tree. */
    public const val MANIFEST: String = "manifest.json"

    /** Value of the manifest `format` field. */
    public const val FORMAT: String = "heartbeat-config"

    /** Current tree version; a newer version is refused instead of being read partially. */
    public const val VERSION: Int = 1
}

/** Bounds of a tree; reading stops with an error before any of them is exceeded. */
public object ConfigLimits {
    /** Files in one tree, the manifest included. */
    public const val FILES: Int = 2048

    /** UTF-8 bytes of one file: an interface spec may take 2 MiB plus its metadata. */
    public const val FILE_BYTES: Int = 4 * 1024 * 1024

    /** UTF-8 bytes of all files of one tree. */
    public const val TOTAL_BYTES: Long = 32L * 1024 * 1024

    /** Files of one unit. */
    public const val UNIT_FILES: Int = 128

    /** Segments of one path. */
    public const val DEPTH: Int = 8

    /** Characters of one path. */
    public const val PATH_CHARS: Int = 512
}

/**
 * `/`-separated path of a file relative to the tree root, e.g. `harnesses/review/harness.json`. Segments start with
 * a letter, digit or `_` and contain letters, digits, `_`, `-` and `.`; hidden files (a leading dot), `..`, empty
 * segments and backslashes are not paths of the tree.
 */
@JvmInline
@Serializable
public value class ConfigPath(public val value: String) {
    init {
        require(isValid(value)) { "Invalid configuration path" }
    }

    /** Path segments, at least one. */
    public val segments: List<String> get() = value.split(SEPARATOR)

    /** First segment: the section directory, or the file name of a root file such as the manifest. */
    public val section: String get() = value.substringBefore(SEPARATOR)

    /** Path inside [section]; empty for a root file. */
    public val inSection: String get() = value.substringAfter(SEPARATOR, "")

    override fun toString(): String = value

    /** Validation and construction of paths. */
    public companion object {
        private const val SEPARATOR = '/'
        private val SEGMENT = Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,99}")

        /** Whether [value] is a path of the tree. */
        public fun isValid(value: String): Boolean {
            if (value.isEmpty() || value.length > ConfigLimits.PATH_CHARS) return false
            val segments = value.split(SEPARATOR)
            return segments.size <= ConfigLimits.DEPTH && segments.all { SEGMENT.matches(it) && !it.endsWith('.') }
        }

        /** [value] as a path, or null when it is not a path of the tree. */
        public fun parse(value: String): ConfigPath? = value.takeIf(::isValid)?.let(::ConfigPath)

        /** Path of [segments] joined with `/`. */
        public fun of(vararg segments: String): ConfigPath = ConfigPath(segments.joinToString(SEPARATOR.toString()))
    }
}

/** One text file of a tree; [toString] never shows the text. */
public data class ConfigFile(val path: ConfigPath, val text: String) {
    /** UTF-8 size of [text]. */
    public val bytes: Int get() = text.encodeToByteArray().size

    override fun toString(): String = "ConfigFile($path, ${text.length} chars)"
}
