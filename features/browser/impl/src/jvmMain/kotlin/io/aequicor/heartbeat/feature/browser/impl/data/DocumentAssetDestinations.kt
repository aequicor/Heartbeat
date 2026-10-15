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
 * Rejects a host when any resolved address is loopback, private, link-local, CGNAT, ULA, multicast or unspecified.
 *
 * The shared OkHttp engine resolves through the same JVM [InetAddress] cache (`networkaddress.cache.ttl`, 30 s by
 * default), so the connection uses the addresses checked here; a rebinding answer arriving between the check and
 * the connect can only win if that cache entry expires in the intervening milliseconds.
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
