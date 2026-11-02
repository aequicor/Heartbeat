package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.datastore.stringKey
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8

/** Receipt storage shares the native-entry mutex in StudioHelperAttempts; this codec grants no new ownership. */
internal fun helperReceiptKey(helper: HelperId) = stringKey("helper_${helper.value.encodeUtf8().sha256().hex()}")

/** Decoder diagnostics may contain private answers, so expose only their failure type. */
internal fun decodeHelperReceipts(raw: String?): Map<String, StudioHelperReceipt> {
    if (raw == null) return emptyMap()
    return try {
        Json.decodeFromString<Map<String, StudioHelperReceipt>>(raw).also { records ->
            check(records.all { (key, receipt) -> key == receipt.request.value }) { "Mismatched helper receipt key" }
        }
    } catch (failure: IllegalArgumentException) {
        throw HelperReceiptStorageException(failure::class.simpleName.orEmpty())
    }
}

/** Avoid leaking decoder payloads in diagnostics. */
private class HelperReceiptStorageException(type: String) : IllegalStateException("Unreadable helper receipts ($type)")
