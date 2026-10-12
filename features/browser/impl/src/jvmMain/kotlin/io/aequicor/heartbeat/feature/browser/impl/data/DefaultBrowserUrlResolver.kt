package io.aequicor.heartbeat.feature.browser.impl.data

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserUrlResolver
import java.net.URI

/** JVM URI adapter, including RFC 3986 query-only references unsupported by URI.resolve. */
@ContributesBinding(AppScope::class)
@Inject
internal class DefaultBrowserUrlResolver : BrowserUrlResolver {
    override fun resolve(base: String, reference: String): String {
        val parsed = URI.create(reference)
        return when {
            reference.startsWith('?') -> base.substringBefore('#').substringBefore('?') + reference
            reference.startsWith('#') -> base.substringBefore('#') + reference
            else -> URI.create(base).resolve(parsed).toString()
        }
    }
}
