package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserCommand
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserReferrer
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserReferrerPolicy
import org.cef.network.CefRequest
import org.cef.network.CefRequest.ReferrerPolicy

/** Copy values while the callback owns its request; never retain a native request across dispatch. */
internal fun CefRequest.linkedNavigation(): BrowserCommand.Load = BrowserCommand.Load(
    url,
    BrowserReferrer(referrerURL.orEmpty(), referrerPolicy.toBrowserPolicy()),
)

/** Unknown native sentinel fails closed. */
private fun ReferrerPolicy.toBrowserPolicy(): BrowserReferrerPolicy = when (this) {
    ReferrerPolicy.REFERRER_POLICY_DEFAULT -> BrowserReferrerPolicy.Default

    ReferrerPolicy.REFERRER_POLICY_CLEAR_REFERRER_ON_TRANSITION_FROM_SECURE_TO_INSECURE ->
        BrowserReferrerPolicy.NoReferrerWhenDowngrade

    ReferrerPolicy.REFERRER_POLICY_REDUCE_REFERRER_GRANULARITY_ON_TRANSITION_CROSS_ORIGIN ->
        BrowserReferrerPolicy.StrictOriginWhenCrossOrigin

    ReferrerPolicy.REFERRER_POLICY_ORIGIN_ONLY_ON_TRANSITION_CROSS_ORIGIN -> BrowserReferrerPolicy.OriginWhenCrossOrigin

    ReferrerPolicy.REFERRER_POLICY_NEVER_CLEAR_REFERRER -> BrowserReferrerPolicy.UnsafeUrl

    ReferrerPolicy.REFERRER_POLICY_ORIGIN -> BrowserReferrerPolicy.Origin

    ReferrerPolicy.REFERRER_POLICY_CLEAR_REFERRER_ON_TRANSITION_CROSS_ORIGIN -> BrowserReferrerPolicy.SameOrigin

    ReferrerPolicy.REFERRER_POLICY_ORIGIN_CLEAR_ON_TRANSITION_FROM_SECURE_TO_INSECURE ->
        BrowserReferrerPolicy.StrictOrigin

    ReferrerPolicy.REFERRER_POLICY_NO_REFERRER -> BrowserReferrerPolicy.NoReferrer

    ReferrerPolicy.REFERRER_POLICY_NUM_VALUES -> BrowserReferrerPolicy.NoReferrer
}

/** All actual Chromium policies keep their exact meaning. */
internal fun BrowserReferrerPolicy.toCefPolicy(): ReferrerPolicy = when (this) {
    BrowserReferrerPolicy.Default -> ReferrerPolicy.REFERRER_POLICY_DEFAULT

    BrowserReferrerPolicy.NoReferrerWhenDowngrade ->
        ReferrerPolicy.REFERRER_POLICY_CLEAR_REFERRER_ON_TRANSITION_FROM_SECURE_TO_INSECURE

    BrowserReferrerPolicy.StrictOriginWhenCrossOrigin ->
        ReferrerPolicy.REFERRER_POLICY_REDUCE_REFERRER_GRANULARITY_ON_TRANSITION_CROSS_ORIGIN

    BrowserReferrerPolicy.OriginWhenCrossOrigin -> ReferrerPolicy.REFERRER_POLICY_ORIGIN_ONLY_ON_TRANSITION_CROSS_ORIGIN

    BrowserReferrerPolicy.UnsafeUrl -> ReferrerPolicy.REFERRER_POLICY_NEVER_CLEAR_REFERRER

    BrowserReferrerPolicy.Origin -> ReferrerPolicy.REFERRER_POLICY_ORIGIN

    BrowserReferrerPolicy.SameOrigin -> ReferrerPolicy.REFERRER_POLICY_CLEAR_REFERRER_ON_TRANSITION_CROSS_ORIGIN

    BrowserReferrerPolicy.StrictOrigin ->
        ReferrerPolicy.REFERRER_POLICY_ORIGIN_CLEAR_ON_TRANSITION_FROM_SECURE_TO_INSECURE

    BrowserReferrerPolicy.NoReferrer -> ReferrerPolicy.REFERRER_POLICY_NO_REFERRER
}
