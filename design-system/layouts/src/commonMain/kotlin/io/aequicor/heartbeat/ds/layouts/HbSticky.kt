package io.aequicor.heartbeat.ds.layouts

import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable

/** A real lazy-list header, pinned until the next section replaces it. */
public fun LazyListScope.hbStickyHeader(key: String, content: @Composable LazyItemScope.(Int) -> Unit) {
    stickyHeader(key = key, contentType = "hb-section-header", content = content)
}
