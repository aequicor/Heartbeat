package io.aequicor.heartbeat.feature.planningmode.impl.domain

import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.planningmode.api.Plan
import io.aequicor.heartbeat.feature.planningmode.api.PlanAuthor
import io.aequicor.heartbeat.feature.planningmode.api.PlanChat
import io.aequicor.heartbeat.feature.planningmode.api.PlanMode
import io.aequicor.heartbeat.feature.planningmode.api.PlanStatus
import io.aequicor.heartbeat.feature.planningmode.api.PlanningEffect
import io.aequicor.heartbeat.feature.planningmode.api.PlanningIntent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Clock
import kotlin.time.Instant

class PlanningEffectsTest {
    private val index = MemoryIndex()
    private val files = MemoryFiles()
    private val effects = PlanningEffects(index, files, FixedClock)
    private val scope = Recorder()

    @Test
    fun `nothing stored loads no plans`() = runTest {
        effects.handle(PlanningEffect.Load, scope)
        assertEquals(listOf<PlanningIntent>(PlanningIntent.Internal.Loaded(emptyMap(), emptyMap())), scope.sent)
    }

    @Test
    fun `load joins records with their files and drops bindings of missing plans`() = runTest {
        index.stored = PlanIndex(
            listOf(record(Chat, revision = 2), record(Other, revision = 1)),
            listOf(PlanBinding(Session, Chat), PlanBinding(Next, PlanChat("gone"))),
        )
        files.texts[Chat] = PlanFileText("# A", isExternal = false)
        effects.handle(PlanningEffect.Load, scope)
        val loaded = scope.sent.single() as PlanningIntent.Internal.Loaded
        assertEquals("# A", loaded.plans.getValue(Chat).text)
        assertEquals("", loaded.plans.getValue(Other).text)
        assertEquals(mapOf(Session to Chat), loaded.bindings)
        assertEquals(emptySet(), loaded.external)
    }

    @Test
    fun `a file edited outside the studio loads as a new external revision`() = runTest {
        index.stored = PlanIndex(listOf(record(Chat, revision = 2, status = PlanStatus.Approved)))
        files.texts[Chat] = PlanFileText("# Edited", isExternal = true)
        effects.handle(PlanningEffect.Load, scope)
        val loaded = scope.sent.single() as PlanningIntent.Internal.Loaded
        val plan = loaded.plans.getValue(Chat)
        assertEquals(setOf(Chat), loaded.external)
        assertEquals(Plan(Chat, PlanMode.Planning, PlanStatus.Draft, 3, "# Edited", Now, PlanAuthor.External), plan)
    }

    @Test
    fun `a plan written before an interrupted index save keeps its own revision`() = runTest {
        index.stored = PlanIndex(listOf(record(Chat, revision = 1)))
        files.texts[Chat] = PlanFileText("# Newer", isExternal = false, revision = 2)
        effects.handle(PlanningEffect.Load, scope)
        val plan = (scope.sent.single() as PlanningIntent.Internal.Loaded).plans.getValue(Chat)
        assertEquals(2, plan.revision)
        assertEquals("# Newer", plan.text)
        effects.handle(PlanningEffect.Persist(mapOf(Chat to plan), emptyMap(), setOf(Chat), 1), scope)
        assertEquals(emptyList(), files.log)
    }

    @Test
    fun `an unreadable index fails the load`() = runTest {
        index.isBroken = true
        assertFailsWith<IllegalStateException> { effects.handle(PlanningEffect.Load, scope) }
    }

    @Test
    fun `persist writes newer files before the index and keeps texts out of the index`() = runTest {
        val plan = record(Chat, revision = 1).copy(text = "# B")
        effects.handle(PlanningEffect.Persist(mapOf(Chat to plan), mapOf(Session to Chat), setOf(Chat), 3), scope)
        assertEquals(listOf("write $Chat", "index"), files.log + index.log)
        assertEquals("# B", files.texts.getValue(Chat).text)
        assertEquals(PlanIndex(listOf(plan.copy(text = "")), listOf(PlanBinding(Session, Chat))), index.stored)
    }

    @Test
    fun `stale saves never bring older plans back`() = runTest {
        val newer = record(Chat, revision = 2).copy(text = "# New")
        val older = record(Chat, revision = 1).copy(text = "# Old")
        effects.handle(PlanningEffect.Persist(mapOf(Chat to newer), emptyMap(), setOf(Chat), 5), scope)
        effects.handle(PlanningEffect.Persist(mapOf(Chat to older), emptyMap(), setOf(Chat), 4), scope)
        assertEquals("# New", files.texts.getValue(Chat).text)
        assertEquals(PlanIndex(listOf(newer.copy(text = ""))), index.stored)
    }

    @Test
    fun `persist without the plan deletes its files`() = runTest {
        files.texts[Chat] = PlanFileText("# A", isExternal = false)
        effects.handle(PlanningEffect.Persist(emptyMap(), emptyMap(), setOf(Chat), 1), scope)
        assertEquals(emptyMap(), files.texts)
        assertEquals(PlanIndex(), index.stored)
    }

    private fun record(chat: PlanChat, revision: Long, status: PlanStatus = PlanStatus.Draft) =
        Plan(chat, PlanMode.Planning, status, revision, "", Instant.fromEpochSeconds(1), PlanAuthor.Agent)

    private class MemoryIndex : PlanIndexStore {
        var stored: PlanIndex? = null
        var isBroken = false
        val log = mutableListOf<String>()

        override suspend fun load(): PlanIndex? = if (isBroken) error("unreadable") else stored

        override suspend fun save(index: PlanIndex) {
            stored = index
            log += "index"
        }
    }

    private class MemoryFiles : PlanFiles {
        val texts = mutableMapOf<PlanChat, PlanFileText>()
        val log = mutableListOf<String>()

        override suspend fun read(chat: PlanChat): PlanFileText? = texts[chat]

        override suspend fun write(chat: PlanChat, text: String, revision: Long) {
            texts[chat] = PlanFileText(text, isExternal = false, revision = revision)
            log += "write $chat"
        }

        override suspend fun delete(chat: PlanChat) {
            texts.remove(chat)
        }
    }

    private class Recorder : EffectScope<PlanningIntent> {
        val sent = mutableListOf<PlanningIntent>()

        override suspend fun send(intent: PlanningIntent): SendResult {
            sent += intent
            return SendResult.Accepted
        }
    }

    private object FixedClock : Clock {
        override fun now(): Instant = Now
    }

    private companion object {
        val Chat = PlanChat("chat-1")
        val Other = PlanChat("chat-2")
        val Now: Instant = Instant.fromEpochSeconds(5_000)
        val Session = SessionRef(EngineId("pi"), SessionSourceId("s"), "one")
        val Next = SessionRef(EngineId("pi"), SessionSourceId("s"), "two")
    }
}
