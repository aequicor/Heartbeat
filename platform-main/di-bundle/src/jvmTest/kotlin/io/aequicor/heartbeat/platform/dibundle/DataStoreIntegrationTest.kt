package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.datastore.DataEvent
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.Retention
import io.aequicor.heartbeat.core.datastore.StorageOwner
import io.aequicor.heartbeat.core.datastore.stringKey
import io.aequicor.heartbeat.core.di.OwnedScope
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.core.profilefacade.ProfileSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class DataStoreIntegrationTest {

    private val persisted = PersistedProfile()
    private val app = createGraphFactory<TestAppGraph.Factory>().create(persisted)
    private val spec = KeyValueSpec("settings")
    private val key = stringKey("k")

    private val ProfileSession.stores get() = (graph as TestStorageAccessors).stores

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = runTest {
        app.closeAndAwaitStorages()
        Dispatchers.resetMain()
        File(persisted.storageRoot).deleteRecursively()
    }

    @Test
    fun `app and profile storages are separate owners`() = runTest {
        val session = app.profileSessions.open(ProfileId("alice"))

        assertEquals(StorageOwner.App, app.appStores.owner)
        assertEquals(StorageOwner.Profile(ProfileId("alice")), session.stores.owner)
        assertSame(session.stores, (session.graph as TestStorageAccessors).stores)

        app.appStores.keyValue(spec).set(key, "app")
        session.stores.keyValue(spec).set(key, "alice")
        assertEquals("app", app.appStores.keyValue(spec).get(key))
        assertEquals("alice", session.stores.keyValue(spec).get(key))
    }

    @Test
    fun `profile storages close with the profile and come back with it`() = runTest {
        val alice = app.profileSessions.open(ProfileId("alice"))
        val cached = alice.stores.keyValue(spec)
        cached.set(key, "alice")

        val bob = app.profileSessions.open(ProfileId("bob"))
        assertFailsWith<IllegalStateException> { alice.stores.keyValue(spec) }
        assertFailsWith<IllegalStateException> { cached.get(key) }
        assertFailsWith<IllegalStateException> { cached.set(key, "late write") }
        assertNull(bob.stores.keyValue(spec).get(key))

        val aliceAgain = app.profileSessions.open(ProfileId("alice"))
        assertEquals("alice", aliceAgain.stores.keyValue(spec).get(key))
        assertFailsWith<IllegalStateException> { cached.get(key) }
    }

    @Test
    fun `an app event reaches the records of every owner`() = runTest {
        val signedOut = DataEvent("auth.signed_out")
        val session = app.profileSessions.open(ProfileId("alice"))
        app.appStores.keyValue(spec).set(key, "app", Retention.untilEvent(signedOut))
        session.stores.keyValue(spec).set(key, "alice", Retention.untilEvent(signedOut))

        app.appStores.fire(signedOut)

        assertNull(app.appStores.keyValue(spec).get(key))
        assertNull(session.stores.keyValue(spec).get(key))
    }

    @Test
    fun `a profile is wiped only when it is not active`() = runTest {
        val id = ProfileId("alice")
        app.profileSessions.open(id).stores.keyValue(spec).set(key, "alice")

        assertFailsWith<IllegalStateException> { app.storageMaintenance.wipeProfile(id) }
        app.profileSessions.close()
        app.storageMaintenance.wipeProfile(id)

        assertNull(app.profileSessions.open(id).stores.keyValue(spec).get(key))
    }

    @Test
    fun `closing the app scope closes the app storages`() = runTest {
        app.appStores.keyValue(spec)
        (app.appScope as OwnedScope).close()

        assertFailsWith<IllegalStateException> { app.appStores.keyValue(spec) }
    }
}
