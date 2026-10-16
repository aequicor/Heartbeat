package io.aequicor.heartbeat.feature.projectconfig.impl.data

import io.aequicor.heartbeat.feature.projectconfig.api.ConfigDigest
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigUnit
import okio.ByteString.Companion.encodeUtf8

/** SHA-256 of the unit's paths and texts in canonical order. */
internal val ConfigUnit.digest: ConfigDigest
    get() = ConfigDigest(
        files.joinToString("") { "${it.path.value}\u0000${it.text}\u0000" }.encodeUtf8().sha256().hex(),
    )
