package io.aequicor.heartbeat.feature.harness.impl.data.ownership.transfer

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipCheckpoint
import io.aequicor.heartbeat.feature.harness.api.process.PORTABLE_PROCESS_CHECKPOINT_BYTES
import io.aequicor.heartbeat.feature.harness.api.process.PortableProcessCheckpoint
import io.aequicor.heartbeat.feature.harness.api.process.decodePortableProcessCheckpoint
import io.aequicor.heartbeat.feature.harness.impl.data.ownership.GitHubOwnershipBackend
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.transfer.OwnershipTransferArtifacts
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8

/** Immutable allowlisted artifacts in the coordinator's fixed service branch, never arbitrary caller paths. */
internal class GitHubOwnershipTransferArtifacts(private val backend: GitHubOwnershipBackend) :
    OwnershipTransferArtifacts {
    private val log = Log.tag("OwnershipArtifacts")

    override suspend fun store(checkpoint: PortableProcessCheckpoint): OwnershipCheckpoint? = guarded {
        val content = Json.encodeToString(checkpoint)
        val bytes = content.encodeUtf8()
        if (bytes.size > PORTABLE_PROCESS_CHECKPOINT_BYTES) {
            null
        } else {
            val digest = bytes.sha256().hex()
            if (backend.storeCheckpoint(digest, content)) OwnershipCheckpoint(digest, digest) else null
        }
    }

    override suspend fun load(pointer: OwnershipCheckpoint): PortableProcessCheckpoint? = guarded {
        if (pointer.reference != pointer.digest) {
            null
        } else {
            backend.checkpoint(pointer.digest)?.let {
                // Backend verifies raw byte digest before the strict size-limited decoder sees the manifest.
                decodePortableProcessCheckpoint(it.encodeToByteArray())
            }
        }
    }

    private suspend fun <T> guarded(block: suspend () -> T?): T? = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        log.w(IllegalStateException("Process checkpoint is unavailable")) {
            "process checkpoint rejected (${error::class.simpleName.orEmpty()})"
        }
        null
    }
}
