package io.aequicor.heartbeat.feature.checklist.impl.data

import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.checklist.api.Checklist
import io.aequicor.heartbeat.feature.checklist.api.ChecklistAnswer
import io.aequicor.heartbeat.feature.checklist.api.ChecklistField
import io.aequicor.heartbeat.feature.checklist.api.ChecklistInput
import io.aequicor.heartbeat.feature.checklist.api.ChecklistIntent
import io.aequicor.heartbeat.feature.checklist.api.ChecklistJournal
import io.aequicor.heartbeat.feature.checklist.api.ChecklistMachineSpec
import io.aequicor.heartbeat.feature.checklist.api.ChecklistOutput
import io.aequicor.heartbeat.feature.checklist.api.ChecklistState
import io.aequicor.heartbeat.feature.checklist.api.event
import io.aequicor.heartbeat.feature.checklist.impl.domain.ChecklistMachine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

internal val TEST_SESSION = SessionRef(EngineId("test"), SessionSourceId("local"), "session")
internal val TEST_REQUEST = RequestId("request")
internal fun testCard() = Checklist(
    "card", TEST_SESSION, TEST_REQUEST, TurnId("turn"), null, null, null,
    "Manual check", listOf(ChecklistField("text", "Notes", ChecklistInput.Text)),
)

class ChecklistPersistenceTest {
    @Test
    fun `durable snapshot restores answers outbox and monotonically newest revision`() = runTest {
        val stores = ChecklistTestStores()
        val storage = ChecklistStorage(stores)
        storage.load()
        val card = testCard().copy(answers = mapOf("text" to ChecklistAnswer(text = "Long answer".repeat(1000))))
        val journal = ChecklistJournal(listOf(card), listOf(card.event()), revision = 2)
        storage.save(journal)
        storage.save(ChecklistJournal(revision = 1))
        assertEquals(journal, ChecklistStorage(stores).load())
        assertEquals(journal, storage.observe().first())
    }

    @Test
    fun `unreadable journal fails without overwriting or exposing answers`() = runTest {
        val stores = ChecklistTestStores()
        val values = stores.keyValue(KeyValueSpec("checklist_journal"))
        values.values.value = mapOf("journal" to "private invalid answers")
        val failure = assertFailsWith<IllegalArgumentException> { ChecklistStorage(stores).load() }
        assertFalse(failure.toString().contains("private invalid answers"))
        assertEquals("private invalid answers", values.values.value["journal"])
    }
}

/** The real spec and real persistence effects, with synchronous feedback for deterministic IO failures. */
internal class PersistingChecklist(private val storage: ChecklistStorage, initial: ChecklistJournal) :
    ChecklistMachine {
    private val log = io.aequicor.heartbeat.core.logging.Log.tag("ChecklistTest")
    override val name = "Checklist"
    override val state = MutableStateFlow<ChecklistState>(ChecklistState.Ready(initial))
    override val outputs: Flow<ChecklistOutput> = emptyFlow()
    override suspend fun send(intent: ChecklistIntent): SendResult {
        val resolution = ChecklistMachineSpec.resolve(state.value, intent) ?: return SendResult.Ignored
        state.value = resolution.to
        for (effect in resolution.effects) {
            try {
                ChecklistEffects(storage).handle(
                    effect,
                    object : EffectScope<ChecklistIntent> {
                        override suspend fun send(intent: ChecklistIntent): SendResult = this@PersistingChecklist.send(
                            intent,
                        )
                    },
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: IllegalStateException) {
                log.w(e) { "Expected persistence failure" }
                send(ChecklistIntent.Internal.Failed)
            }
        }
        return SendResult.Accepted
    }
}
