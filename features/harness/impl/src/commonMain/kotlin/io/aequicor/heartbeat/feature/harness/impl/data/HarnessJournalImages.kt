package io.aequicor.heartbeat.feature.harness.impl.data

import io.aequicor.heartbeat.core.datastore.Expiry
import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.core.datastore.Retention
import io.aequicor.heartbeat.core.datastore.stringKey
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessStorageUncertain
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Instant

/** Permanent images are inline; expiring images only reference a native-retention blob. */
@Serializable
internal data class HarnessJournalImage(
    val text: String? = null,
    val blob: String? = null,
    val expiresAt: Instant? = null,
) {
    init {
        require((text == null) != (blob == null))
        require(
            if (blob == null) {
                expiresAt == null
            } else {
                expiresAt != null && blob.startsWith(
                    "pending_image.",
                ) && stringKey(blob).name == blob
            },
        )
    }
    override fun toString(): String = "HarnessJournalImage(***)"
}

@Serializable
internal data class HarnessPendingCommit(
    val operationId: String,
    val phase: HarnessCommitPhase,
    val before: Map<String, HarnessJournalImage?>,
    val after: Map<String, HarnessJournalImage?>,
    val version: Int = 1,
) {
    init {
        require(operationId.isNotBlank() && version == 1 && after.isNotEmpty())
        require(if (phase == HarnessCommitPhase.Prepared) before.keys == after.keys else before.isEmpty())
        require(after.keys.all { it != "pending" && !it.startsWith("pending_image.") && stringKey(it).name == it })
    }
    fun matches(prepared: HarnessPendingCommit): Boolean =
        operationId == prepared.operationId && after == prepared.after &&
            (phase == HarnessCommitPhase.Committed || before == prepared.before)
    override fun toString(): String = "HarnessPendingCommit(phase=$phase, ***)"
}

/** Absolute expiry belongs to DataStore, so a crashed journal cannot retain terminal payload forever. */
internal class HarnessJournalImages(private val store: KeyValueStore, private val clock: Clock) {
    fun references(
        operation: String,
        side: String,
        values: Map<String, HarnessRawValue?>,
    ): Map<String, HarnessJournalImage?> = values.mapValues { (key, value) ->
        value?.let {
            if (it.expiresAt == null) {
                HarnessJournalImage(text = it.text)
            } else {
                HarnessJournalImage(
                    blob = "pending_image.${harnessOperationId(operation, side, key)}",
                    expiresAt = it.expiresAt,
                )
            }
        }
    }

    suspend fun persist(values: Map<String, HarnessRawValue?>, references: Map<String, HarnessJournalImage?>) {
        references.forEach { (name, image) ->
            image?.blob?.let { blob ->
                store.set(
                    stringKey(blob),
                    checkNotNull(values[name]).text,
                    Retention.expiring(Expiry.At(checkNotNull(image.expiresAt))),
                )
            }
        }
    }

    suspend fun apply(values: Map<String, HarnessJournalImage?>) {
        values.forEach { (name, image) ->
            val value = resolve(image)
            val field = stringKey(name)
            if (value == null) {
                store.remove(field)
            } else {
                val retention = image?.expiresAt?.let { Retention.expiring(Expiry.At(it)) } ?: Retention.Permanent
                store.set(field, value, retention)
            }
        }
    }

    suspend fun verify(values: Map<String, HarnessJournalImage?>) {
        values.forEach { (name, image) ->
            if (store.get(
                    stringKey(name),
                ) != resolve(image)
            ) {
                throw HarnessStorageUncertain()
            }
        }
    }

    suspend fun verifyRaw(values: Map<String, HarnessRawValue?>) {
        values.forEach { (name, expected) ->
            val text = expected?.takeUnless { it.expiresAt?.let { at -> at <= clock.now() } == true }?.text
            if (store.get(stringKey(name)) != text) throw HarnessStorageUncertain()
        }
    }

    suspend fun clean(record: HarnessPendingCommit) {
        (record.before.values + record.after.values).mapNotNull { it?.blob }.distinct().forEach {
            store.remove(
                stringKey(it),
            )
        }
    }

    private suspend fun resolve(image: HarnessJournalImage?): String? = when {
        image == null || image.expiresAt?.let { it <= clock.now() } == true -> null
        image.text != null -> image.text
        else -> store.get(stringKey(checkNotNull(image.blob))) ?: throw HarnessStorageUncertain()
    }
}
