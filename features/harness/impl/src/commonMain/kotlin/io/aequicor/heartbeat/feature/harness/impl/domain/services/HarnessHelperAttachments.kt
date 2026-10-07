package io.aequicor.heartbeat.feature.harness.impl.domain.services

/** Confirms durable attachment of the binding's exact native session, with no implicit engine open. */
internal fun interface HarnessHelperAttachments {
    suspend fun ensureAttached(binding: HarnessHelperBinding): Boolean
}
