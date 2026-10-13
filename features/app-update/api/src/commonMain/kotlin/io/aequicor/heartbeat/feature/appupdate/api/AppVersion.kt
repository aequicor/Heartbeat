package io.aequicor.heartbeat.feature.appupdate.api

/**
 * A release version `MAJOR.MINOR.PATCH` as the release workflow publishes it; a release tag carries a leading `v`.
 * Ordered numerically: `1.10.0` is newer than `1.9.3`.
 */
public data class AppVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int =
        compareValuesBy(this, other, AppVersion::major, AppVersion::minor, AppVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"

    /** Parsing of versions and release tags. */
    public companion object {
        /** The version `1.2.3`, or null for anything else (pre-releases and build suffixes included). */
        public fun parse(value: String): AppVersion? {
            val match = Version.matchEntire(value) ?: return null
            val (major, minor, patch) = match.destructured.toList().map { it.toIntOrNull() ?: return null }
            return AppVersion(major, minor, patch)
        }

        /** The version of a release tag `v1.2.3`, or null: other tags of the repository are not app releases. */
        public fun fromTag(tag: String): AppVersion? = if (tag.startsWith('v')) parse(tag.drop(1)) else null

        private val Version = Regex("""(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)""")
    }
}
