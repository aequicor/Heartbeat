package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.feature.browser.api.BrowserLoadFailure
import io.aequicor.heartbeat.feature.browser.api.BrowserPage
import io.aequicor.heartbeat.feature.browser.api.BrowserSecurity
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserCommand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

/** Main-dispatcher failure recovery, bound to the current tab, failure instance and native document. */
internal class DesktopBrowserFailures(
    private val getFactory: () -> DesktopBrowserPanelFactory,
    private val entries: Map<String, DesktopBrowserEntry>,
    private val isEnabled: () -> Boolean,
    private val selected: () -> String?,
    private val onChanged: suspend (String?) -> Unit,
    private val operations: MutableMap<String, Pair<String?, Job>>,
) {
    private val factory get() = getFactory()
    private val log = Log.tag("BrowserFailures")
    suspend fun retryNetworkErrors() {
        if (!isEnabled() || !factory.errorPagesEnabled.value) return
        val failed = entries.values.filter {
            !it.guard.isRestricted && !it.surface.pages.value.isLoading &&
                it.surface.pages.value.failure?.isNetworkRetryable == true
        }
        for (entry in failed) {
            entry.surface.execute(BrowserCommand.Reload)
        }
        if (failed.isNotEmpty()) log.i { "Retrying browser pages after network recovery: count=${failed.size}" }
    }

    suspend fun proceedCertificate(tabId: String, failure: BrowserLoadFailure) {
        val entry = matchingWarning(tabId, failure) ?: return
        if (!failure.isProceedAllowed) return
        val operationId = Uuid.random().toString()
        operations[operationId] = tabId to currentCoroutineContext().job
        try {
            entry.lock.withLock { proceedCertificate(entry, tabId, failure) }
        } finally {
            operations.remove(operationId)
        }
    }

    private suspend fun proceedCertificate(entry: DesktopBrowserEntry, tabId: String, failure: BrowserLoadFailure) {
        if (matchingWarning(tabId, failure) !== entry) return
        val isAccepted = entry.interstitial.proceed(failure.url, failure.id) {
            val isAllowed = matchingWarning(tabId, failure) === entry
            // Mark before native execution: another tab must never race a cached native exception.
            if (isAllowed) {
                factory.rememberCertificateException(failure.url)
                onChanged(null)
            }
            isAllowed && matchingWarning(tabId, failure) === entry
        }
        if (isAccepted && entries[tabId] === entry) {
            entry.update(entry.tab.page.copy(security = BrowserSecurity.CertificateException))
            onChanged(null)
            log.i { "User proceeded through a Chromium certificate warning" }
        }
    }

    suspend fun returnToSafety(tabId: String, failure: BrowserLoadFailure) {
        val entry = matchingWarning(tabId, failure) ?: return
        val isBackAvailable = entry.tab.page.isBackAvailable
        log.i { "User returned from a Chromium certificate warning: closedTab=${!isBackAvailable}" }
        if (isBackAvailable) {
            entry.surface.execute(BrowserCommand.Back)
            onChanged(null)
        } else {
            onChanged(tabId)
        }
    }

    private fun matchingWarning(tabId: String, failure: BrowserLoadFailure): DesktopBrowserEntry? =
        entries[tabId]?.takeIf {
            isEnabled() && factory.errorPagesEnabled.value && selected() == tabId &&
                !it.guard.isRestricted && it.tab.page.failure == failure && failure.isCertificate &&
                it.surface.pages.value.failure?.id == failure.id
        }

    fun requireAgentSafety(entry: DesktopBrowserEntry) {
        val page = entry.surface.pages.value
        require(!entry.surface.isCertificateWarningActive) { "Native certificate warning requires human control" }
        require(page.failure?.isCertificate != true && !factory.isWithheldFromAgents(page.url)) {
            "Certificate warnings and exception pages require human control"
        }
    }

    /** A probe failure leaves the warning blocking; no optimistic bypass is ever displayed. */
    suspend fun project(entry: DesktopBrowserEntry, page: BrowserPage): BrowserPage {
        val failure = page.failure
        if (failure == null || !failure.isCertificate || page.isLoading) return page
        if (!factory.errorPagesEnabled.value || entry.guard.isRestricted) return page
        if (failure.code !in OVERRIDABLE_CERTIFICATE_ERRORS) return page
        return try {
            page.copy(
                failure = failure.copy(isProceedAllowed = entry.interstitial.isProceedAllowed(failure.url, failure.id)),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(error.redactedFailure()) { "Chromium certificate warning could not be inspected" }
            page
        }
    }

    private companion object {
        val OVERRIDABLE_CERTIFICATE_ERRORS = setOf(
            "ERR_CERT_DATE_INVALID",
            "ERR_CERT_AUTHORITY_INVALID",
            "ERR_CERT_COMMON_NAME_INVALID",
        )
    }
}
