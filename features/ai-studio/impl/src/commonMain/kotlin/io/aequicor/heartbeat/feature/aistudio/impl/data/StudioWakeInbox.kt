package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.stringKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal enum class WakeReceipt { Submitting, Accepted }

private val WakeInboxSpec = KeyValueSpec("ai_studio_wake_inbox")
private val ReceiptsKey = stringKey("receipts")

/**
 * Native request ids do not promise deduplication. Save before submitting and after acceptance; a crash in between
 * leaves Submitting, which is explicitly refused instead of blindly repeating an externally visible turn.
 */
@SingleIn(ProfileScope::class)
@Inject
internal class StudioWakeInbox(
    @ForScope(ProfileScope::class) stores: DataStores,
) {
    private val store = stores.keyValue(WakeInboxSpec)
    private val lock = Mutex()

    suspend fun receipt(request: RequestId): WakeReceipt? = lock.withLock { read()[request.value] }

    suspend fun submitting(request: RequestId) = lock.withLock {
        val receipts = read()
        check(request.value !in receipts) { "Wake acceptance is already known or uncertain" }
        store.set(ReceiptsKey, Json.encodeToString(receipts + (request.value to WakeReceipt.Submitting)))
    }

    suspend fun accepted(request: RequestId) = lock.withLock {
        store.set(ReceiptsKey, Json.encodeToString(read() + (request.value to WakeReceipt.Accepted)))
    }

    /** Safe only after the submission gate proved that native submission never began. */
    suspend fun cancelledBeforeSubmission(request: RequestId) = lock.withLock {
        val receipts = read()
        if (receipts[request.value] == WakeReceipt.Submitting) {
            store.set(ReceiptsKey, Json.encodeToString(receipts - request.value))
        }
    }

    private suspend fun read(): Map<String, WakeReceipt> {
        val raw = store.get(ReceiptsKey) ?: return emptyMap()
        return try {
            Json.decodeFromString(raw)
        } catch (e: IllegalArgumentException) {
            throw e.withoutReceiptText()
        }
    }
}

/** Receipt decoder errors can include stored identifiers; retain only the failure kind. */
private fun Exception.withoutReceiptText(): IllegalStateException =
    IllegalStateException("Unreadable wake receipts (${this::class.simpleName.orEmpty()})")
