package io.aequicor.heartbeat.feature.browser.api

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle

/** Desktop error pages, certificate decisions and address-bar security; opt-in. */
public val BrowserErrorPages: FeatureToggle.Flag = FeatureToggle.Flag(
    key = "browser.error_pages",
    description = "Страницы ошибок и безопасность встроенного браузера",
    default = false,
)

/** Main-document failure. Native diagnostics are excluded; [code] is a Chromium symbolic error name. */
public data class BrowserLoadFailure(
    val url: String,
    val code: String,
    val isCertificate: Boolean = false,
    val isProceedAllowed: Boolean = false,
    val id: String = "",
) {
    /** Only connectivity errors are retried after an observed offline-to-online transition. */
    val isNetworkRetryable: Boolean
        get() = !isCertificate && code in NETWORK_ERRORS
}

/** Connection indicator, never inferred from an uncommitted address draft. */
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
