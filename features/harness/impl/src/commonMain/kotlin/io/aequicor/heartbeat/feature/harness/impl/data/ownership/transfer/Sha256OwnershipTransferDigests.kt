package io.aequicor.heartbeat.feature.harness.impl.data.ownership.transfer

import io.aequicor.heartbeat.feature.harness.api.process.GitHubTaskIdentity
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipKey
import io.aequicor.heartbeat.feature.harness.api.process.PortableProcessCheckpoint
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.transfer.OwnershipTransferDigests
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8

/** Same canonical SHA-256 encodings as the immutable GitHub artifact transport, without domain IO dependencies. */
internal class Sha256OwnershipTransferDigests : OwnershipTransferDigests {
    override fun key(identity: GitHubTaskIdentity): OwnershipKey =
        OwnershipKey(identity.canonical.encodeUtf8().sha256().hex())

    override fun checkpoint(manifest: PortableProcessCheckpoint): String =
        Json.encodeToString(manifest).encodeUtf8().sha256().hex()
}
