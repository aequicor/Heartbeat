package io.aequicor.heartbeat.feature.aiengine.connections.impl.data

import androidx.room.RoomDatabase
import io.aequicor.heartbeat.core.datastore.DataEvent
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.DatabaseSpec
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.core.datastore.StorageOwner
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelCheckGeneration
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelCheckReceipt
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelection
import io.aequicor.heartbeat.feature.aiengine.connections.impl.domain.ModelCheckEvaluation
import io.aequicor.heartbeat.feature.aiengine.connections.impl.domain.checkReport
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class StoredModelCheckReportsTest {
    @Test
    fun `success stores only a text free receipt and a warning retains both aspects through reload`() = runTest {
        val disk = MemorySelectionStore()
        val reports = reports(disk)
        assertTrue(reports.observe().first().isEmpty())
        assertEquals(0, disk.writes, "Opening diagnostics must not write or invoke probes")
        reports.put(checkReport().copy(response = ModelCheckEvaluation.response("response-text-canary")))
        val successful = reports(disk).observe().first().single()
        assertTrue(successful.isPassed)
        assertNull(successful.report)
        assertFalse(disk.values.value.getValue(ModelCheckReportsKey.name).contains("response-text-canary"))
        val warning = checkReport(isWarning = true)
        reports.put(warning)
        assertEquals(warning, reports(disk).observe().first().single().report)
        assertFalse(disk.spec.areValuesLogged)
    }

    @Test
    fun `stored definition is explicit and a missing version cannot inherit the current definition`() {
        val receipt = ModelCheckReceipt.from(checkReport())
        val encoded = Json.encodeToString(ModelCheckReceipt.serializer(), receipt)
        val root = Json.parseToJsonElement(encoded) as JsonObject
        val revision = root.getValue("revision") as JsonObject
        assertTrue("definition" in revision)
        val missing = JsonObject(root + ("revision" to JsonObject(revision - "definition")))
        assertFailsWith<SerializationException> {
            Json.decodeFromString(ModelCheckReceipt.serializer(), missing.toString())
        }
    }

    @Test
    fun `a later accepted run replaces its target by publication order not wall clock ordering`() = runTest {
        val disk = MemorySelectionStore()
        val reports = reports(disk)
        val old = checkReport(isWarning = true).copy(checkedAt = Instant.fromEpochSeconds(100))
        reports.put(old)
        val next = checkReport().copy(run = ModelCheckGeneration("33333333-3333-3333-3333-333333333333"))
        reports.put(next)
        val stored = reports.observe().first().single()
        assertEquals(next.run, stored.run)
        assertEquals(next.checkedAt, stored.checkedAt)
        assertTrue(stored.isPassed)
    }

    @Test
    fun `capacity eviction is bounded and never changes model choices or their default`() = runTest {
        val disk = MemorySelectionStore()
        val target = checkReport().revision.target
        val choice = ModelSelection().withDefault(target)
        val choiceJson = Json.encodeToString(ModelSelection.serializer(), choice)
        disk.values.value = mapOf(ModelSelectionKey.name to choiceJson)
        val reports = reports(disk)
        val added = StoredModelCheckReports.MAX_RECORDS + 3
        repeat(added) { reports.put(checkReport("model-$it", isWarning = true)) }
        val current = reports.observe().first()
        assertEquals(StoredModelCheckReports.MAX_RECORDS, current.size)
        assertEquals("model-3", current.first().revision.target.model.value)
        assertEquals("model-${added - 1}", current.last().revision.target.model.value)
        assertEquals(choiceJson, disk.values.value[ModelSelectionKey.name])
    }

    @Test
    fun `removing a binding keeps other bindings and leaves selection untouched`() = runTest {
        val disk = MemorySelectionStore()
        val reports = reports(disk)
        val first = checkReport(isWarning = true)
        val choiceJson = Json.encodeToString(
            ModelSelection.serializer(),
            ModelSelection().withDefault(first.revision.target),
        )
        disk.values.value = mapOf(ModelSelectionKey.name to choiceJson)
        val other = first.copy(
            revision = first.revision.copy(target = first.revision.target.copy(binding = EngineBindingId("other"))),
        )
        reports.put(first)
        reports.put(other)
        reports.remove(first.revision.target.binding)
        assertEquals(listOf(other), reports.observe().first().map { it.report })
        assertEquals(choiceJson, disk.values.value[ModelSelectionKey.name])
    }

    @Test
    fun `a failed write preserves the previous observation without fabricating new completion`() = runTest {
        val disk = MemorySelectionStore()
        val reports = reports(disk)
        val before = checkReport(isWarning = true)
        reports.put(before)
        disk.isWriteRejected = true
        assertFailsWith<IllegalStateException> { reports.put(checkReport()) }
        assertEquals(before, reports.observe().first().single().report)
    }

    @Test
    fun `cancelling a queued publisher cannot replace another report`() = runTest {
        val disk = MemorySelectionStore()
        val reports = reports(disk)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        disk.beforeRead = {
            entered.complete(Unit)
            release.await()
        }
        val first = async { reports.put(checkReport("first")) }
        entered.await()
        val cancelled = async { reports.put(checkReport("cancelled")) }
        cancelled.cancelAndJoin()
        release.complete(Unit)
        first.await()
        assertEquals(listOf("first"), reports.observe().first().map { it.revision.target.model.value })
    }

    @Test
    fun `eviction of diagnostic storage means missing evidence without deleting choices`() = runTest {
        val disk = MemorySelectionStore()
        val reports = reports(disk)
        val choices = Json.encodeToString(
            ModelSelection.serializer(),
            ModelSelection().withDefault(checkReport().revision.target),
        )
        disk.values.value = mapOf(ModelSelectionKey.name to choices)
        reports.put(checkReport(isWarning = true))
        disk.values.value -= ModelCheckReportsKey.name
        assertTrue(reports(disk).observe().first().isEmpty())
        assertEquals(choices, disk.values.value[ModelSelectionKey.name])
    }

    private fun reports(disk: MemorySelectionStore): StoredModelCheckReports = StoredModelCheckReports(
        object : DataStores {
            override val owner = StorageOwner.App
            override fun filesDirectory(name: String): String = error("Not used")
            override fun keyValue(spec: KeyValueSpec): KeyValueStore {
                assertEquals(ModelSelectionSpec, spec)
                return disk
            }
            override fun <T : RoomDatabase> database(spec: DatabaseSpec<T>): T = error("Not used")
            override suspend fun fire(event: DataEvent) = Unit
        },
    )
}
