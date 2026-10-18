package io.aequicor.heartbeat.feature.browser.api

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle

/** Desktop error pages, certificate decisions and address-bar security. */
public val BrowserErrorPages: FeatureToggle.Flag = FeatureToggle.Flag(
    key = "browser.error_pages",
    description = "Страницы ошибок и безопасность встроенного браузера",
    default = true,
)

/**
 * Main-document failure. Native diagnostics are excluded; [code] is a Chromium symbolic error name.
 * [isSubmission] marks a non-GET navigation (a form post): the server may already have received it.
 */
public data class BrowserLoadFailure(
    val url: String,
    val code: String,
    val isCertificate: Boolean = false,
    val isProceedAllowed: Boolean = false,
    val id: String = "",
    val isSubmission: Boolean = false,
) {
    /**
     * Only connectivity errors of plain loads are retried after an observed offline-to-online transition;
     * like Chrome, a submission is never resent without the user.
     */
    val isNetworkRetryable: Boolean
        get() = !isCertificate && !isSubmission && code in NETWORK_ERRORS
}

/** Connection indicator of the last committed document, never inferred from an address draft or a pending load. */
public enum class BrowserSecurity {
    Unknown,
    Https,
    Http,
    CertificateException,
}

private val NETWORK_ERRORS = setOf(
    "ERR_NAME_NOT_RESOLVED",
    "ERR_CONNECTION_REFUSED",
    "ERR_CONNECTION_TIMED_OUT",
    "ERR_CONNECTION_RESET",
    "ERR_INTERNET_DISCONNECTED",
    "ERR_NETWORK_CHANGED",
)
