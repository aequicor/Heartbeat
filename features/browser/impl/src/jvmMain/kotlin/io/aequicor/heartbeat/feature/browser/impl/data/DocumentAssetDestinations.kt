package io.aequicor.heartbeat.feature.browser.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.feature.searchengine.api.isPublicWebUrl
import kotlinx.coroutines.withContext
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Decides whether a document asset host may be contacted from the application process. The host relay bypasses
 * Chromium's Private Network Access, so every hop must resolve only to public addresses.
 */
internal fun interface DocumentAssetDestinations {
    suspend fun isPublic(host: String): Boolean
}

/**
 * Rejects a host when any resolved address is loopback, private, link-local, CGNAT, ULA, NAT64, multicast or
 * unspecified.
 *
 * The shared engine resolves again when it connects (or a configured proxy resolves for it), so DNS rebinding and
 * split-horizon proxies can still answer differently. Assets are HTTPS-only with certificate validation: such a
 * local endpoint cannot present a valid certificate for the public name, so no request reaches it. This check
 * keeps literal and plainly resolving local destinations off the TLS path.
 */
@ContributesBinding(ProfileScope::class)
@Inject
internal class ResolvedDocumentAssetDestinations(private val dispatchers: DispatcherProvider) :
    DocumentAssetDestinations {
    private val log = Log.tag("DocumentAssetDestinations")

    override suspend fun isPublic(host: String): Boolean = withContext(dispatchers.io) {
        try {
            val addresses = InetAddress.getAllByName(host)
            addresses.isNotEmpty() && addresses.all(InetAddress::isPublicAddress)
        } catch (error: UnknownHostException) {
            log.w(error.redactedFailure()) { "Document asset host did not resolve" }
            false
        }
    }
}

/** Reuses the literal classifier of `web_fetch` so both routes share one definition of a public address. */
internal fun InetAddress.isPublicAddress(): Boolean {
    val literal = if (this is Inet6Address) "[${hostAddress.substringBefore('%')}]" else hostAddress
    return isPublicWebUrl("https://$literal/")
}
