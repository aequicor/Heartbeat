package io.aequicor.heartbeat.feature.planningmode.impl.domain

import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.planningmode.api.Plan
import io.aequicor.heartbeat.feature.planningmode.api.PlanAuthor
import io.aequicor.heartbeat.feature.planningmode.api.PlanChat
import io.aequicor.heartbeat.feature.planningmode.api.PlanLimits
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
        assertEquals(emptySet(), loaded.repaired)
    }

    @Test
    fun `a file edited outside the studio loads as a new external revision`() = runTest {
        index.stored = PlanIndex(listOf(record(Chat, revision = 2, status = PlanStatus.Approved)))
        files.texts[Chat] = PlanFileText("# Edited", isExternal = true)
        effects.handle(PlanningEffect.Load, scope)
        val loaded = scope.sent.single() as PlanningIntent.Internal.Loaded
        val plan = loaded.plans.getValue(Chat)
        assertEquals(setOf(Chat), loaded.repaired)
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
    fun `a recovered newer file is a draft of unknown author`() = runTest {
        index.stored = PlanIndex(listOf(record(Chat, revision = 1, status = PlanStatus.Approved)))
        files.texts[Chat] = PlanFileText("# Newer", isExternal = false, revision = 2)
        effects.handle(PlanningEffect.Load, scope)
        val loaded = scope.sent.single() as PlanningIntent.Internal.Loaded
        val plan = loaded.plans.getValue(Chat)
        assertEquals(PlanStatus.Draft, plan.status)
        assertEquals(PlanAuthor.External, plan.updatedBy)
        assertEquals(Now, plan.updatedAt)
        assertEquals(setOf(Chat), loaded.repaired)
    }

    @Test
    fun `an external edit never repeats a revision the studio wrote`() = runTest {
        index.stored = PlanIndex(listOf(record(Chat, revision = 2)))
        files.texts[Chat] = PlanFileText("# Edited", isExternal = true, revision = 5)
        effects.handle(PlanningEffect.Load, scope)
        val plan = (scope.sent.single() as PlanningIntent.Internal.Loaded).plans.getValue(Chat)
        assertEquals(6, plan.revision)
        effects.handle(PlanningEffect.Persist(mapOf(Chat to plan), emptyMap(), setOf(Chat), 0), scope)
        assertEquals(PlanFileText("# Edited", isExternal = false, revision = 6), files.texts[Chat])
    }

    @Test
    fun `an oversized file is neither loaded nor overwritten`() = runTest {
        index.stored = PlanIndex(listOf(record(Chat, revision = 2)))
        files.texts[Chat] = PlanFileText("", isExternal = true, revision = 2, isOversized = true)
        effects.handle(PlanningEffect.Load, scope)
        val plan = (scope.sent.single() as PlanningIntent.Internal.Loaded).plans.getValue(Chat)
        assertEquals(2, plan.revision)
        effects.handle(PlanningEffect.Persist(mapOf(Chat to plan), emptyMap(), emptySet(), 1), scope)
        val edited = plan.copy(revision = 3, text = "# Studio", updatedBy = PlanAuthor.User)
        effects.handle(PlanningEffect.Persist(mapOf(Chat to edited), emptyMap(), setOf(Chat), 2), scope)
        assertEquals(emptyList(), files.log, "a studio change does not replace the oversized file")
        assertEquals(PlanIndex(listOf(edited.copy(text = ""))), index.stored)
    }

    @Test
    fun `an external text longer than a plan loads empty and is protected until taken over`() = runTest {
        index.stored = PlanIndex(listOf(record(Chat, revision = 2, status = PlanStatus.Approved)))
        val long = "x".repeat(PlanLimits.MAX_CHARS + 1)
        files.texts[Chat] = PlanFileText(long, isExternal = true, revision = 2)
        effects.handle(PlanningEffect.Load, scope)
        val loaded = scope.sent.single() as PlanningIntent.Internal.Loaded
        val plan = loaded.plans.getValue(Chat)
        assertEquals(record(Chat, revision = 2, status = PlanStatus.Approved), plan)
        assertEquals(emptySet(), loaded.repaired)

        val edited = plan.copy(revision = 3, text = "# Studio")
        effects.handle(PlanningEffect.Persist(mapOf(Chat to edited), emptyMap(), setOf(Chat), 1), scope)
        assertEquals(long, files.texts.getValue(Chat).text)

        files.texts[Chat] = PlanFileText("# Short", isExternal = true, revision = 2)
        effects.handle(PlanningEffect.Persist(mapOf(Chat to edited), emptyMap(), setOf(Chat), 2), scope)
        assertEquals("# Short", files.texts.getValue(Chat).text, "a shortened file is not overwritten either")

        val takenOver = edited.copy(revision = 4, text = "# Short", updatedBy = PlanAuthor.External)
        effects.handle(PlanningEffect.Persist(mapOf(Chat to takenOver), emptyMap(), setOf(Chat), 3), scope)
        assertEquals(PlanFileText("# Short", isExternal = false, revision = 4), files.texts[Chat])
        val next = takenOver.copy(revision = 5, text = "# Next")
        effects.handle(PlanningEffect.Persist(mapOf(Chat to next), emptyMap(), setOf(Chat), 4), scope)
        assertEquals("# Next", files.texts.getValue(Chat).text)
    }

    @Test
    fun `an external edit is loaded without trailing whitespace`() = runTest {
        index.stored = PlanIndex(listOf(record(Chat, revision = 2)))
        files.texts[Chat] = PlanFileText("# Edited\n\n", isExternal = true, revision = 2)
        effects.handle(PlanningEffect.Load, scope)
        val plan = (scope.sent.single() as PlanningIntent.Internal.Loaded).plans.getValue(Chat)
        assertEquals("# Edited", plan.text)
        assertEquals(3, plan.revision)
    }

    @Test
    fun `a failed file write is repeated by the next save`() = runTest {
        val plan = record(Chat, revision = 1).copy(text = "# B")
        files.isWriteBroken = true
        assertFailsWith<IllegalStateException> {
            effects.handle(PlanningEffect.Persist(mapOf(Chat to plan), emptyMap(), setOf(Chat), 1), scope)
        }
        files.isWriteBroken = false
        effects.handle(PlanningEffect.Persist(mapOf(Chat to plan), mapOf(Session to Chat), emptySet(), 2), scope)
        assertEquals(PlanFileText("# B", isExternal = false, revision = 1), files.texts[Chat])
        assertEquals(PlanIndex(listOf(plan.copy(text = "")), listOf(PlanBinding(Session, Chat))), index.stored)
    }

    @Test
    fun `a failed delete does not block a new plan of the chat and is repeated for orphans`() = runTest {
        val plan = record(Chat, revision = 3).copy(text = "# A")
        effects.handle(PlanningEffect.Persist(mapOf(Chat to plan), emptyMap(), setOf(Chat), 1), scope)
        files.isDeleteBroken = true
        effects.handle(PlanningEffect.Persist(emptyMap(), emptyMap(), setOf(Chat), 2), scope)
        assertEquals(PlanIndex(), index.stored, "the index is saved despite the failed delete")
        files.isDeleteBroken = false
        val fresh = record(Chat, revision = 0)
        effects.handle(PlanningEffect.Persist(mapOf(Chat to fresh), emptyMap(), emptySet(), 3), scope)
        assertEquals(PlanFileText("", isExternal = false, revision = 0), files.texts[Chat])

        val other = record(Other, revision = 1).copy(text = "# O")
        effects.handle(PlanningEffect.Persist(mapOf(Chat to fresh, Other to other), emptyMap(), emptySet(), 4), scope)
        files.isDeleteBroken = true
        effects.handle(PlanningEffect.Persist(mapOf(Chat to fresh), emptyMap(), setOf(Other), 5), scope)
        assertEquals(PlanIndex(listOf(fresh)), index.stored)
        effects.handle(PlanningEffect.Persist(mapOf(Chat to fresh), emptyMap(), emptySet(), 6), scope)
        assertEquals(setOf(Chat, Other), files.texts.keys, "still failing")
        files.isDeleteBroken = false
        effects.handle(PlanningEffect.Persist(mapOf(Chat to fresh), emptyMap(), emptySet(), 7), scope)
        assertEquals(setOf(Chat), files.texts.keys)
    }

    @Test
    fun `a new plan saved while a delete fails survives a restart`() = runTest {
        val plan = record(Chat, revision = 1).copy(text = "# A")
        effects.handle(PlanningEffect.Persist(mapOf(Chat to plan), emptyMap(), setOf(Chat), 1), scope)
        files.isDeleteBroken = true
        effects.handle(PlanningEffect.Persist(emptyMap(), emptyMap(), setOf(Chat), 2), scope)
        val other = record(Other, revision = 1).copy(text = "# O")
        effects.handle(PlanningEffect.Persist(mapOf(Other to other), emptyMap(), setOf(Other), 3), scope)
        assertEquals(PlanIndex(listOf(other.copy(text = ""))), index.stored)

        files.isDeleteBroken = false
        val restarted = PlanningEffects(index, files, FixedClock)
        restarted.handle(PlanningEffect.Load, scope)
        val loaded = scope.sent.single() as PlanningIntent.Internal.Loaded
        assertEquals(setOf(Other), loaded.plans.keys)
        assertEquals(setOf(Other), files.texts.keys, "the orphan of the forgotten chat is deleted on load")
    }

    @Test
    fun `orphans are kept when nothing was stored or a delete fails on load`() = runTest {
        files.texts[Chat] = PlanFileText("# A", isExternal = false, revision = 1)
        effects.handle(PlanningEffect.Load, scope)
        assertEquals(setOf(Chat), files.texts.keys)

        index.stored = PlanIndex()
        files.isDeleteBroken = true
        PlanningEffects(index, files, FixedClock).handle(PlanningEffect.Load, scope)
        assertEquals(setOf(Chat), files.texts.keys)
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
        var isWriteBroken = false
        var isDeleteBroken = false

        override suspend fun read(chat: PlanChat): PlanFileText? = texts[chat]

        override suspend fun write(chat: PlanChat, text: String, revision: Long) {
            check(!isWriteBroken) { "disk full" }
            texts[chat] = PlanFileText(text, isExternal = false, revision = revision)
            log += "write $chat"
        }

        override suspend fun delete(chat: PlanChat) {
            check(!isDeleteBroken) { "busy" }
            texts.remove(chat)
            log += "delete $chat"
        }

        override suspend fun chats(): Set<PlanChat> = texts.keys.toSet()
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
