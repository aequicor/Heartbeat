package io.aequicor.heartbeat.core.datastore.impl

import io.aequicor.heartbeat.core.datastore.DataEvent
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.Retention
import io.aequicor.heartbeat.core.datastore.StorageOwner
import io.aequicor.heartbeat.core.datastore.stringKey
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.SYSTEM
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes

class EventJournalTest {

    private fun storageTest(block: suspend TestScope.(StorageTestEnv) -> Unit) = runTest {
        val env = StorageTestEnv(this)
        try {
            block(env)
            assertEquals(emptyList(), env.errors)
        } finally {
            withContext(NonCancellable) { env.dispose() }
        }
    }

    @Test
    fun `a delayed older event cannot replace its latest persisted timestamp`() = storageTest { env ->
        val file = env.layout.journalFile(StorageOwner.App)
        val journal = EventJournal(file, FileSystem.SYSTEM)
        val signedOut = DataEvent("auth.signed_out")
        val reset = DataEvent("cache.reset")

        journal.record(signedOut, 200)
        journal.record(reset, 150)
        journal.record(signedOut, 100)

        assertEquals(
            mapOf(signedOut.name to 200L, reset.name to 150L),
            EventJournal(file, FileSystem.SYSTEM).read(),
        )
    }

    @Test
    fun `closed profile cleanup keeps the newer event after a delayed older fire`() = storageTest { env ->
        val registry = env.registry()
        val owner = StorageOwner.Profile(ProfileId("alice"))
        val session = env.newScope("alice", env.app)
        val settings = KeyValueSpec("settings")
        val signedOut = DataEvent("auth.signed_out")
        val anotherEvent = DataEvent("cache.reset")
        val bound = stringKey("bound")
        val independent = stringKey("independent")
        val store = registry.attach(owner, session).keyValue(settings)
        val olderFire = env.retentionClock.now()
        advanceTimeBy(1.minutes)
        store.set(bound, "remove on sign out", Retention.untilEvent(signedOut))
        store.set(independent, "keep until reset", Retention.untilEvent(anotherEvent))
        session.close()
        advanceTimeBy(1.minutes)
        val journal = EventJournal(env.layout.journalFile(StorageOwner.App), FileSystem.SYSTEM)
        journal.record(signedOut, env.retentionClock.now())
        journal.record(signedOut, olderFire)
        env.restartProcess()

        val reopened = env.registry().attach(owner, env.newScope("alice", env.app)).keyValue(settings)

        assertNull(reopened.get(bound))
        assertEquals("keep until reset", reopened.get(independent))
    }
}
