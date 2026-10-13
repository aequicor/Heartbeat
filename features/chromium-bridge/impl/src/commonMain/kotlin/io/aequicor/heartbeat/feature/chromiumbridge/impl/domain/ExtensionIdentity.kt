package io.aequicor.heartbeat.feature.chromiumbridge.impl.domain

/**
 * Extensions allowed to start the host and talk to the app. The id of the unpacked extension is pinned by the
 * public `key` in `chromium-plugin/manifest.json` (first 128 bits of its SHA-256, digits mapped to `a`–`p`);
 * a store-published build would add its own id here.
 */
internal object ExtensionIdentity {
    /** Extension ids. */
    val ids: Set<String> = setOf("dngnkjkjppehmiaebojfebjcfkcpdjki")

    /** Origins Chrome passes to the host and lists in `allowed_origins`. */
    val origins: List<String> = ids.sorted().map { "chrome-extension://$it/" }

    /** Whether a host introduced an allowed extension. */
    fun isAllowedOrigin(origin: String): Boolean = origin in origins
}
