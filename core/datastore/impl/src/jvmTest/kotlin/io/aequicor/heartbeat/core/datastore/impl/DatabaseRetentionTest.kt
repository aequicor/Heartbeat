package io.aequicor.heartbeat.core.datastore.impl

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.aequicor.heartbeat.core.datastore.DataEvent
import io.aequicor.heartbeat.core.datastore.DatabaseSpec
import io.aequicor.heartbeat.core.datastore.Expiry
import io.aequicor.heartbeat.core.datastore.Retention
import io.aequicor.heartbeat.core.datastore.StorageOwner
import io.aequicor.heartbeat.core.logging.LogLevel
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.SYSTEM
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class DatabaseRetentionTest {

    private val spec = DatabaseSpec<TestDatabase>("notes", { TestDatabase_Impl() })
    private val signedOut = DataEvent("auth.signed_out")
    private val alice = StorageOwner.Profile(ProfileId("alice"))

    private fun dbTest(block: suspend TestScope.(StorageTestEnv) -> Unit) = runTest {
        val env = StorageTestEnv(this)
        try {
            block(env)
            assertEquals(emptyList(), env.errors)
        } finally {
            withContext(NonCancellable) { env.dispose() }
        }
    }

    private fun StorageTestEnv.note(id: String, retention: Retention) =
        NoteEntity(id, "text of $id", RecordRetentionsImpl(retentionClock).stamp(retention))

    /** Room runs on the test dispatcher: lets the pending work (timer, invalidation) run until [condition] holds. */
    private suspend fun TestScope.eventually(condition: suspend () -> Boolean) {
        repeat(ATTEMPTS) {
            if (condition()) return
            runCurrent()
        }
        assertTrue(condition(), "condition not reached")
    }

    @Test
    fun `the database lives in the owner directory and is opened once`() = dbTest { env ->
        val stores = env.registry(JvmRoomBuilderFactory()).attach(alice, env.newScope("profile", env.app))

        val db = stores.database(spec)
        db.notes().insert(TagEntity("t"))

        assertSame(db, stores.database(spec))
        assertTrue(FileSystem.SYSTEM.exists(env.layout.databaseFile(alice, "notes")))
    }

    @Test
    fun `expired rows are deleted by the timer and observers see it`() = dbTest { env ->
        val db = env.registry(JvmRoomBuilderFactory()).attach(StorageOwner.App, env.app).database(spec)
        db.notes().insert(env.note("expiring", Retention.expiring(Expiry.After(1.hours))))
        db.notes().insert(env.note("permanent", Retention.Permanent))
        db.notes().insert(TagEntity("tag"))
        runCurrent()
        eventually { db.notes().observeIds().first() == listOf("expiring", "permanent") }

        advanceTimeBy(1.hours + 1.minutes)
        eventually { db.notes().observeIds().first() == listOf("permanent") }

        assertEquals(1, db.notes().tagCount(), "tables without retention columns are untouched")
        eventually { env.logged(LogLevel.INFO, DB_LOG_TAG).any { "purged 1 expired rows" in it } }
    }

    @Test
    fun `an event deletes its rows now, and in closed databases on open`() = dbTest { env ->
        val registry = env.registry(JvmRoomBuilderFactory())
        val app = registry.attach(StorageOwner.App, env.app)
        val appDb = app.database(spec)
        appDb.notes().insert(env.note("bound", Retention.untilEvent(signedOut)))
        appDb.notes().insert(env.note("other", Retention.untilEvent(DataEvent("other"))))
        val session = env.newScope("profile", env.app)
        val aliceNotes = registry.attach(alice, session).database(spec).notes()
        aliceNotes.insert(env.note("bound", Retention.untilEvent(signedOut)))
        session.close()

        advanceTimeBy(1.minutes)
        app.fire(signedOut)
        assertEquals(listOf("other"), appDb.notes().ids())

        val reopened = registry.attach(alice, env.newScope("profile", env.app)).database(spec)
        assertEquals(emptyList(), reopened.notes().ids())
    }

    @Test
    fun `rows written after the event survive reopening`() = dbTest { env ->
        val registry = env.registry(JvmRoomBuilderFactory())
        val app = registry.attach(StorageOwner.App, env.app)
        app.fire(signedOut)
        advanceTimeBy(1.minutes)
        val session = env.newScope("profile", env.app)
        val aliceNotes = registry.attach(alice, session).database(spec).notes()
        aliceNotes.insert(env.note("later", Retention.untilEvent(signedOut)))
        session.close()

        val reopened = registry.attach(alice, env.newScope("profile", env.app)).database(spec)
        assertEquals(listOf("later"), reopened.notes().ids())
    }

    @Test
    fun `rooms and room members are purged by expiry and events`() = dbTest { env ->
        val stores = env.registry(JvmRoomBuilderFactory()).attach(StorageOwner.App, env.app)
        val dao = stores.database(spec).rooms()
        val retentions = RecordRetentionsImpl(env.retentionClock)
        val policies = mapOf(
            "expiring" to Retention.expiring(Expiry.After(1.hours)),
            "event" to Retention.untilEvent(signedOut),
            "permanent" to Retention.Permanent,
        )
        policies.forEach { (id, policy) ->
            dao.insert(RoomEntity(id, retentions.stamp(policy)))
            dao.insert(RoomMemberEntity(id, retentions.stamp(policy)))
        }
        runCurrent()

        advanceTimeBy(1.hours + 1.minutes)
        eventually { dao.roomIds() == listOf("event", "permanent") }
        eventually { dao.memberIds() == listOf("event", "permanent") }
        stores.fire(signedOut)

        assertEquals(listOf("permanent"), dao.roomIds())
        assertEquals(listOf("permanent"), dao.memberIds())
    }

    @Test
    fun `a database without retention tables finishes its timer`() = dbTest { env ->
        val retention = DatabaseRetention("permanent", env.retentionClock) { emptyMap() }
        val db = JvmRoomBuilderFactory()
            .builder(env.layout.databaseFile(StorageOwner.App, "permanent").toString()) { PermanentTestDatabase_Impl() }
            .setDriver(DirectoryCreatingDriver(BundledSQLiteDriver(), FileSystem.SYSTEM))
            .setQueryCoroutineContext(env.dispatcher)
            .addCallback(retention)
            .build() as PermanentTestDatabase
        try {
            db.tags().insert(TagEntity("tag"))
            var subscriptions = 0
            val deadlines = retention.nextDeadline(db).onStart {
                subscriptions++
                if (subscriptions > 1) throw CancellationException("completed deadline flow was collected again")
            }

            runRetentionTimer("permanent", env.retentionClock, deadlines) { retention.purgeExpired(db) }

            assertEquals(1, subscriptions)
            assertEquals(1, db.tags().count())
        } finally {
            db.close()
        }
    }

    @Test
    fun `closing the parent releases profile databases before wiping their files`() = dbTest { env ->
        val registry = env.registry(JvmRoomBuilderFactory())
        val session = env.newScope("profile", env.app)
        val db = registry.attach(alice, session).database(spec)
        db.notes().insert(TagEntity("tag"))

        env.app.close()
        assertTrue(session.isClosed)
        registry.wipeProfile(alice.id)

        assertFalse(FileSystem.SYSTEM.exists(env.layout.profileDir(alice.id)))
    }

    private companion object {
        const val ATTEMPTS = 100
    }
}
