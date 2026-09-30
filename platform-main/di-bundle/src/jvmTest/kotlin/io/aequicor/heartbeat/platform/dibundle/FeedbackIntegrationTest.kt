package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.jsonKey
import io.aequicor.heartbeat.core.di.OwnedScope
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.core.profilefacade.ProfileSession
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionConfiguration
import io.aequicor.heartbeat.feature.feedback.api.FeedbackChange
import io.aequicor.heartbeat.feature.feedback.api.FeedbackEnabled
import io.aequicor.heartbeat.feature.feedback.api.FeedbackIntent
import io.aequicor.heartbeat.feature.feedback.api.FeedbackMachineKey
import io.aequicor.heartbeat.feature.feedback.api.FeedbackOutcome
import io.aequicor.heartbeat.feature.feedback.api.FeedbackRecord
import io.aequicor.heartbeat.feature.feedback.api.FeedbackState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class FeedbackIntegrationTest {
    private val persisted = PersistedProfile()
    private val app = createGraphFactory<TestAppGraph.Factory>().create(persisted)
    private val toggles = app as TestToggleAccessors

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() {
        (app.appScope as OwnedScope).close()
        Dispatchers.resetMain()
        File(persisted.storageRoot).deleteRecursively()
    }

    @Test
    fun `feedback is a registered toggle disabled by default`() = runTest {
        assertTrue(FeedbackEnabled in toggles.toggleControl.registered)
        assertEquals(false, FeedbackEnabled.default)
        assertEquals(false, toggles.featureToggles.get(FeedbackEnabled))
    }

    @Test
    fun `profile startup registers feedback before opening any screen`() = runTest {
        assertNull(app.machines.find(FeedbackMachineKey))
        app.profileSessions.open(ProfileId("startup"))
        assertNotNull(app.machines.find(FeedbackMachineKey))
        assertEquals(emptyList(), ready().records)
        app.profileSessions.close()
        assertNull(app.machines.find(FeedbackMachineKey))
        assertEquals(
            SendResult.NotRunning,
            app.machines.send(FeedbackMachineKey, FeedbackIntent.Public.Publish(record("closed", "chat"))),
        )
    }

    @Test
    fun `profile journals restore their own feedback without leaking another profile`() = runTest {
        val alice = app.profileSessions.open(ProfileId("alice"))
        ready()
        val aliceRecord = record("operation", "alice-chat")
        publish(aliceRecord)
        alice.awaitStored(aliceRecord)
        app.profileSessions.close()

        val bob = app.profileSessions.open(ProfileId("bob"))
        assertEquals(emptyList(), ready().records)
        val bobRecord = record("operation", "bob-chat")
        publish(bobRecord)
        bob.awaitStored(bobRecord)
        app.profileSessions.close()

        app.profileSessions.open(ProfileId("alice"))
        assertEquals(listOf(aliceRecord), ready().records)
        app.profileSessions.close()
    }

    @Test
    fun `a persisted pending change is recovered as unknown when the profile reopens`() = runTest {
        val first = app.profileSessions.open(ProfileId("pending"))
        ready()
        val pending = record("pending-op", "chat").copy(outcome = FeedbackOutcome.Pending)
        publish(pending)
        assertEquals(pending, ready().records.single())
        first.awaitStored(pending)
        app.profileSessions.close()

        app.profileSessions.open(ProfileId("pending"))
        val recovered = ready().records.single()
        assertEquals(pending.copy(revision = 1, outcome = FeedbackOutcome.Unknown), recovered)
        app.profileSessions.close()
    }

    private suspend fun publish(record: FeedbackRecord) {
        assertEquals(SendResult.Accepted, app.machines.send(FeedbackMachineKey, FeedbackIntent.Public.Publish(record)))
    }

    private suspend fun ready(): FeedbackState.Ready {
        val machine = requireNotNull(app.machines.find(FeedbackMachineKey))
        return bounded("profile ${app.profileSessions.active.value?.id?.value.orEmpty()} feedback load") {
            machine.state.first { it is FeedbackState.Ready } as FeedbackState.Ready
        }
    }

    /** The real KV observation confirms the journal write before closing its owning profile. */
    private suspend fun ProfileSession.awaitStored(record: FeedbackRecord) {
        val stores = (graph as TestStorageAccessors).stores
        bounded("profile ${id.value} feedback ${record.id} persisted") {
            stores.keyValue(FeedbackTestSpec).observe(FeedbackTestRecordsKey).first { records ->
                records?.contains(record) == true
            }
        }
    }

    private suspend fun <T> bounded(checkpoint: String, action: suspend () -> T): T =
        withContext(app.dispatchers.default) {
            try {
                withTimeout(10_000L) { action() }
            } catch (e: TimeoutCancellationException) {
                throw AssertionError("Timed out at $checkpoint", e)
            }
        }

    private fun record(id: String, source: String): FeedbackRecord = FeedbackRecord(
        id,
        source,
        revision = 0,
        createdAt = Instant.fromEpochSeconds(100),
        change = FeedbackChange.Effort(null, "high"),
        outcome = FeedbackOutcome.Applied(SessionConfiguration(ModelId("model"), "high")),
    )
}

private val FeedbackTestSpec = KeyValueSpec("feedback")
private val FeedbackTestRecordsKey = jsonKey("records", ListSerializer(FeedbackRecord.serializer()))
