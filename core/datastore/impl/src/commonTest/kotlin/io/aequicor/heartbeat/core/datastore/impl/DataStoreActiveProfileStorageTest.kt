package io.aequicor.heartbeat.core.datastore.impl

import io.aequicor.heartbeat.core.datastore.StorageOwner
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DataStoreActiveProfileStorageTest {

    @Test
    fun `the active profile survives a process restart and can be cleared`() = runTest {
        val env = StorageTestEnv(this)
        try {
            // one registry per "process": DataStore allows a single active instance per file
            fun storage() = DataStoreActiveProfileStorage(env.registry().attach(StorageOwner.App, env.app))

            val first = storage()
            assertNull(first.read())
            first.write(ProfileId("alice"))
            env.restartProcess()

            val restarted = storage()
            assertEquals(ProfileId("alice"), restarted.read())
            restarted.write(null)
            assertNull(restarted.read())
            assertEquals(emptyList(), env.errors)
        } finally {
            withContext(NonCancellable) { env.dispose() }
        }
    }
}
