package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.datastore.ProfileStorageCleaner
import io.aequicor.heartbeat.core.di.OwnedScope
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.core.secrets.Secret
import io.aequicor.heartbeat.core.secrets.SecretKey
import io.aequicor.heartbeat.core.secrets.SecretRemoval
import io.aequicor.heartbeat.core.secrets.SecretUsage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SecretsIntegrationTest {
    private val persisted = PersistedProfile()
    private val app = createGraphFactory<TestAppGraph.Factory>().create(persisted)
    private val id = ProfileId("secrets-integration")
    private val key = SecretKey("shared")
    private val usage = SecretUsage("engine", "engine-one", "auth")

    @BeforeTest
    fun setUp() {
        val os = System.getProperty("os.name")
        assumeTrue(os.startsWith("Windows") || os.startsWith("Mac"))
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        (app.appScope as OwnedScope).close()
        Dispatchers.resetMain()
        File(persisted.storageRoot).deleteRecursively()
    }

    @Test
    fun profileGraphPersistsSecretsAndReferencesUntilExplicitWipe() = runTest {
        val original = (app.profileSessions.open(id).graph as SecretsAccessors).secrets
        Secret("private".toCharArray()).use { original.write(key, it) }
        original.bind(usage, key)
        app.profileSessions.open(ProfileId("another-profile"))
        assertFailsWith<IllegalStateException> { original.read(key) }
        val reopened = (app.profileSessions.open(id).graph as SecretsAccessors).secrets
        assertEquals(SecretRemoval.InUse(listOf(usage)), reopened.remove(key))
        assertEquals(
            "private",
            assertNotNull(reopened.readFor(usage)).use { it.reveal { chars -> chars.concatToString() } },
        )
        assertFailsWith<IllegalStateException> { app.storageMaintenance.wipeProfile(id) }
        app.profileSessions.close()
        app.storageMaintenance.wipeProfile(id)
        assertNull((app.profileSessions.open(id).graph as SecretsAccessors).secrets.read(key))
    }

    @Test
    fun emptyProfileIdIsRejectedBeforeAnyCleanerRuns() = runTest {
        persisted.beforeProfileWipe = { error("cleaner must not run") }
        assertFailsWith<IllegalArgumentException> { app.storageMaintenance.wipeProfile(ProfileId("")) }
    }

    @Test
    fun reopeningDuringWipeCannotCreateAnAccessibleSecretStore() = runTest {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        persisted.beforeProfileWipe = {
            started.complete(Unit)
            finish.await()
        }
        val wipe = async { app.storageMaintenance.wipeProfile(id) }
        started.await()
        try {
            val reopened = app.profileSessions.open(id)
            assertFailsWith<IllegalStateException> { (reopened.graph as SecretsAccessors).secrets }
        } finally {
            app.profileSessions.close()
            finish.complete(Unit)
            wipe.await()
        }
        persisted.beforeProfileWipe = {}
        assertNull((app.profileSessions.open(id).graph as SecretsAccessors).secrets.read(key))
    }
}

@ContributesIntoSet(AppScope::class)
@Inject
class TestProfileWipeBarrier(private val persisted: PersistedProfile) : ProfileStorageCleaner {
    override suspend fun wipeProfile(id: ProfileId) {
        persisted.beforeProfileWipe()
    }
}
