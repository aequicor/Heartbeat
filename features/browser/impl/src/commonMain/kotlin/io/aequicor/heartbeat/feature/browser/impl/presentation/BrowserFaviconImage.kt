package io.aequicor.heartbeat.feature.browser.impl.presentation

import kotlin.jvm.JvmInline

/**
 * A site icon re-encoded by the app as a small square PNG, never the site's own bytes. Compared by array identity:
 * each decoded icon is a new image.
 */
@JvmInline
internal value class BrowserFaviconImage(val png: ByteArray)
