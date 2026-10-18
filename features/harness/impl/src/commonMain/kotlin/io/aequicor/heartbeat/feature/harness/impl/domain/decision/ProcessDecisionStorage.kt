package io.aequicor.heartbeat.feature.harness.impl.domain.decision

import io.aequicor.heartbeat.feature.harness.api.decision.ProcessDecisionLedger

/** Private profile book, inaccessible through ScriptData or surface method dispatch. Failures never imply consent. */
internal interface ProcessDecisionStorage {
    suspend fun load(): ProcessDecisionLedger
    suspend fun save(ledger: ProcessDecisionLedger, expectedRevision: Long)
}
