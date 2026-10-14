package io.aequicor.heartbeat.feature.appupdate.impl.domain

import io.aequicor.heartbeat.feature.appupdate.api.AppVersion

/**
 * A desktop build the release workflow publishes; the asset name joins the version, [os], [arch] and the file kind.
 * Keep in sync with the asset names of `.github/workflows/release.yml`.
 */
internal enum class ReleasePlatform(val os: String, val arch: String, private val suffix: String) {
    MacOsArm64("macos", "arm64", ".dmg"),
    MacOsX64("macos", "x64", ".dmg"),
    WindowsX64("windows", "x64", "-setup.exe"),
    WindowsArm64("windows", "arm64", "-setup.exe"),
    LinuxX64("linux", "x64", ".deb"),
    ;

    /** System packages are installed by the user through their package manager. */
    val isManualInstallRequired: Boolean
        get() = this == LinuxX64

    /** The release asset of [version] for this platform, e.g. `Heartbeat-1.4.0-macos-arm64.dmg`. */
    fun assetName(version: AppVersion): String = "Heartbeat-$version-$os-$arch$suffix"
}

/** A GitHub repository `owner/name` that publishes the app's releases. */
internal data class GitHubRepository(val owner: String, val name: String) {
    override fun toString(): String = "$owner/$name"

    companion object {
        /** `owner/name`, or null for anything else. */
        fun parse(value: String): GitHubRepository? {
            val parts = value.trim().split('/')
            if (parts.size != 2 || !parts.all(::isSegment)) return null
            return GitHubRepository(parts[0], parts[1])
        }

        private fun isSegment(part: String): Boolean = Segment.matches(part) && part != "." && part != ".."

        private val Segment = Regex("[A-Za-z0-9_.-]+")
    }
}
