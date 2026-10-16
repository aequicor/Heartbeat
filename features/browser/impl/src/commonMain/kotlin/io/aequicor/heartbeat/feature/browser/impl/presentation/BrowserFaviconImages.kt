package io.aequicor.heartbeat.feature.browser.impl.presentation

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.jvm.JvmInline

/**
 * A site icon re-encoded by the app as a small square PNG, never the site's own bytes. Compared by array identity:
 * each decoded icon is a new image.
 */
@JvmInline
internal value class BrowserFaviconImage(val png: ByteArray)

/** Decoded site icons by address, for the tab strip; platforms without tabs never load any. */
internal interface BrowserFaviconImages {
    val images: StateFlow<Map<String, BrowserFaviconImage>>
}

@ContributesBinding(ProfileScope::class)
@Inject
internal class NoBrowserFaviconImages : BrowserFaviconImages {
    override val images: StateFlow<Map<String, BrowserFaviconImage>> = MutableStateFlow(emptyMap())
}
