package io.aequicor.heartbeat.feature.harness.impl.domain.github

import io.aequicor.heartbeat.feature.harness.api.github.GitHubWriteRefusal
import io.aequicor.heartbeat.feature.harness.api.process.ResultRef
import io.aequicor.heartbeat.feature.harness.api.process.WriteKey
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.runtime.OwnershipLocalProcess

/** Protected portable registry operations used by script effects without depending on storage or transports. */
internal interface PortableGitHubAccess {
    suspend fun checkImported(local: OwnershipLocalProcess, candidate: GitHubOperation): ImportedGitHubCheck
    suspend fun reconcileImportedKey(local: OwnershipLocalProcess, key: WriteKey): ImportedGitHubCheck
    suspend fun reserveNative(local: OwnershipLocalProcess, candidate: GitHubOperation): GitHubReservation
}

internal enum class PortableGitHubFailure {
    Identity,
    NativeStopUnconfirmed,
    Unresolved,
    UnsupportedReference,
    RegistryChanged,
    Conflict,
    Quota,
    Storage,
}

/** Every result other than Absent is terminal for this dispatch attempt; Unknown must never fall through to send. */
internal sealed interface ImportedGitHubCheck {
    data object Absent : ImportedGitHubCheck
    data class Confirmed(val result: ResultRef, val marker: String) : ImportedGitHubCheck {
        override fun toString(): String = "ImportedGitHubCheck.Confirmed(***)"
    }
    data class Refused(val reason: GitHubWriteRefusal, val marker: String) : ImportedGitHubCheck {
        override fun toString(): String = "ImportedGitHubCheck.Refused(reason=$reason, ***)"
    }
    data object Conflict : ImportedGitHubCheck
    data object Unknown : ImportedGitHubCheck
    data object Quota : ImportedGitHubCheck
    data class Unavailable(val reason: PortableGitHubFailure) : ImportedGitHubCheck
}
