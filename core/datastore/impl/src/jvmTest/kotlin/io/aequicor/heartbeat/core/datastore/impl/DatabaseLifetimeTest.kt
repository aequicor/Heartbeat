package io.aequicor.heartbeat.core.datastore.impl

import androidx.room.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.datastore.DatabaseSpec
import io.aequicor.heartbeat.core.datastore.StorageOwner
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.SYSTEM
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DatabaseLifetimeTest {
    private val owner = StorageOwner.Profile(ProfileId("alice"))
    private val spec = DatabaseSpec<TestDatabase>("lifetime", { TestDatabase_Impl() })

    @Test
    fun `close drains an external DAO caller before closing SQLite and wipe waits for actual close`() = runTest {
        val env = StorageTestEnv(this)
        val driver = GatedLifetimeDriver()
        val registry = env.lifetimeRegistry(driver)
        val profile = env.newScope("profile", env.app)
        val stores = registry.attach(owner, profile)
        try {
            val database = stores.database(spec)
            database.notes().insert(TagEntity("one"))
            assertEquals(1, database.notes().tagCount())
            driver.isArmed.set(true)
            val read = async { database.notes().tagCount() }
            driver.entered.await()
            profile.close()
            val closed = async { registry.awaitClosed() }
            val wipe = async { registry.wipeProfile(owner.id) }
            runCurrent()
            assertFalse(closed.isCompleted)
            assertFalse(wipe.isCompleted)
            assertEquals(0, driver.closes.get())
            assertTrue(FileSystem.SYSTEM.exists(env.layout.databaseFile(owner, spec.name)))
            assertFailsWith<IllegalStateException> { stores.database(spec) }
            driver.release.countDown()
            assertFailsWith<CancellationException> { read.await() }
            closed.await()
            wipe.await()
            assertTrue(driver.closes.get() > 0)
            assertFalse(FileSystem.SYSTEM.exists(env.layout.profileDir(owner.id)))
            assertFailsWith<CancellationException> { database.notes().tagCount() }
        } finally {
            driver.release.countDown()
            withContext(NonCancellable) {
                profile.close()
                profile.job.join()
                registry.awaitClosed()
                env.dispose()
            }
        }
    }

    @Test
    fun `public writer from an external scope is owned and cancelling cleanup wait does not cancel close`() = runTest {
        val env = StorageTestEnv(this)
        val driver = GatedLifetimeDriver()
        val registry = env.lifetimeRegistry(driver)
        val profile = env.newScope("profile", env.app)
        val stores = registry.attach(owner, profile)
        val gate = CompletableDeferred<Unit>()
        try {
            val database = stores.database(spec)
            database.notes().insert(TagEntity("one"))
            val entered = CompletableDeferred<Unit>()
            val writer = async {
                database.useWriterConnection {
                    withContext(NonCancellable) {
                        entered.complete(Unit)
                        gate.await()
                    }
                }
            }
            entered.await()
            // Active owners do not extend a maintenance snapshot.
            registry.awaitClosed()
            profile.close()
            val cancelledWait = async { registry.awaitClosed() }
            runCurrent()
            assertFalse(cancelledWait.isCompleted)
            cancelledWait.cancelAndJoin()
            assertEquals(0, driver.closes.get())
            gate.complete(Unit)
            assertFailsWith<CancellationException> { writer.await() }
            registry.awaitClosed()
            assertTrue(driver.closes.get() > 0)
        } finally {
            gate.complete(Unit)
            withContext(NonCancellable) {
                profile.close()
                profile.job.join()
                registry.awaitClosed()
                env.dispose()
            }
        }
    }

    @Test
    fun `factory admitted before close stays in cleanup barrier and cannot recreate wiped files`() = runTest {
        val env = StorageTestEnv(this)
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val builders = RoomBuilderFactory { path, factory ->
            entered.complete(Unit)
            check(release.await(10, TimeUnit.SECONDS)) { "Test factory gate was not released" }
            JvmRoomBuilderFactory().builder(path, factory)
        }
        val registry = env.registry(builders)
        val profile = env.newScope("profile", env.app)
        val stores = registry.attach(owner, profile)
        try {
            val opening = async(Dispatchers.Default) {
                assertFailsWith<IllegalStateException> { stores.database(spec) }
            }
            entered.await()
            profile.close()
            val closed = async { registry.awaitClosed() }
            val wipe = async { registry.wipeProfile(owner.id) }
            runCurrent()
            assertFalse(closed.isCompleted)
            assertFalse(wipe.isCompleted)
            release.countDown()
            opening.await()
            closed.await()
            wipe.await()
            assertFalse(FileSystem.SYSTEM.exists(env.layout.profileDir(owner.id)))
        } finally {
            release.countDown()
            withContext(NonCancellable) { env.dispose() }
        }
    }

    @Test
    fun `physical close failure remains observable and refuses file deletion`() = runTest {
        val env = StorageTestEnv(this)
        val driver = GatedLifetimeDriver().apply { hasCloseFailure = true }
        val registry = env.lifetimeRegistry(driver)
        val profile = env.newScope("profile", env.app)
        val stores = registry.attach(owner, profile)
        try {
            stores.database(spec).notes().insert(TagEntity("one"))
            profile.close()
            assertFailsWith<IllegalStateException> { registry.awaitClosed() }
            assertFailsWith<IllegalStateException> { registry.wipeProfile(owner.id) }
            assertTrue(FileSystem.SYSTEM.exists(env.layout.databaseFile(owner, spec.name)))
        } finally {
            withContext(NonCancellable) {
                profile.close()
                profile.job.join()
                assertFailsWith<IllegalStateException> { registry.awaitClosed() }
                env.dispose()
            }
        }
    }

    private fun StorageTestEnv.lifetimeRegistry(driver: SQLiteDriver): StoreRegistry {
        val threaded = object : DispatcherProvider by dispatchers {
            override val io = Dispatchers.IO
        }
        return StoreRegistry(layout, retentionClock, threaded, JvmRoomBuilderFactory(), app, databaseDriver = driver)
    }
}

/** Blocks before native step, so even an old unsafe close fails an assertion instead of crashing the test JVM. */
private class GatedLifetimeDriver : SQLiteDriver {
    private val delegate = BundledSQLiteDriver()
    val isArmed = AtomicBoolean(false)
    val entered = CompletableDeferred<Unit>()
    val release = CountDownLatch(1)
    val closes = AtomicInteger()
    var hasCloseFailure = false

    override fun open(fileName: String): SQLiteConnection {
        val connection = delegate.open(fileName)
        val isClosed = AtomicBoolean(false)
        return object : SQLiteConnection by connection {
            override fun prepare(sql: String): SQLiteStatement {
                val statement = connection.prepare(sql)
                return object : SQLiteStatement by statement {
                    override fun step(): Boolean {
                        if (sql == "SELECT COUNT(*) FROM tags" && isArmed.compareAndSet(true, false)) {
                            entered.complete(Unit)
                            check(release.await(10, TimeUnit.SECONDS)) { "Test gate was not released" }
                            check(!isClosed.get()) {
                                "SQLite was closed while the query was still using its connection"
                            }
                        }
                        return statement.step()
                    }
                }
            }

            override fun close() {
                isClosed.set(true)
                closes.incrementAndGet()
                connection.close()
                check(!hasCloseFailure) { "Injected physical close failure" }
            }
        }
    }
}
