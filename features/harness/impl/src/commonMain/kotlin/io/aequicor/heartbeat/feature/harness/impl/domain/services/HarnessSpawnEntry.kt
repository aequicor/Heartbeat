package io.aequicor.heartbeat.feature.harness.impl.domain.services

import io.aequicor.heartbeat.feature.harness.api.script.ScriptHelper
import io.aequicor.heartbeat.feature.scheduler.api.HelperLease
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job

/**
 * Host registry entry. Closing/job pointers are guarded by the owner's registry mutex. The producer alone writes
 * record/lease/journal flags until it completes; cleanup joins it before taking ownership of those fields.
 */
internal class HarnessSpawnEntry(var record: HarnessSpawnRecord, val isRestored: Boolean) {
    val result = CompletableDeferred<ScriptHelper>()
    var lease: HelperLease? = null
    var hasJournalWriteStarted = false
    var isClosing = false
    var producer: Job? = null
    var monitor: Job? = null
    var cleanup: Job? = null

    fun leaseRequired(): HelperLease = checkNotNull(lease) { "Helper lease is missing" }
    override fun toString(): String = "HarnessSpawnEntry(***)"
}
