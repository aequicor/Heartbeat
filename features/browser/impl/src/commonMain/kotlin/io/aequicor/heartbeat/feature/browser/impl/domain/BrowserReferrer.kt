package io.aequicor.heartbeat.feature.browser.impl.domain

/** Immutable navigation metadata copied before leaving the native request callback. */
internal data class BrowserReferrer(val url: String, val policy: BrowserReferrerPolicy)

/** Web referrer policies; the platform keeps the page's policy when opening a linked document. */
internal enum class BrowserReferrerPolicy {
    Default,
    NoReferrerWhenDowngrade,
    StrictOriginWhenCrossOrigin,
    OriginWhenCrossOrigin,
    UnsafeUrl,
    Origin,
    SameOrigin,
    StrictOrigin,
    NoReferrer,
}
