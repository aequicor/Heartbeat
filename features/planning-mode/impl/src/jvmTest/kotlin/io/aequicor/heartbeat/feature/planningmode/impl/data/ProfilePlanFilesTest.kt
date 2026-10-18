package io.aequicor.heartbeat.feature.planningmode.impl.data

import androidx.room.RoomDatabase
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.datastore.DataEvent
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.DatabaseSpec
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.core.datastore.StorageOwner
import io.aequicor.heartbeat.feature.planningmode.api.PlanChat
import io.aequicor.heartbeat.feature.planningmode.impl.domain.PlanFileText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfilePlanFilesTest {
    private val root = Files.createTempDirectory("plans").toFile()
    private val files = ProfilePlanFiles(Stores(root), Unconfined)

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `missing plan reads as absent`() = runTest {
        assertNull(files.read(Chat))
    }

    @Test
    fun `written plan reads back as the studio's own text`() = runTest {
        files.write(Chat, "# Plan\n\nПлан")
        assertEquals(PlanFileText("# Plan\n\nПлан", isExternal = false), files.read(Chat))
        assertEquals("# Plan\n\nПлан", File(root, "planning/${Chat.value}/plan.md").readText())
        assertFalse(File(root, "planning/${Chat.value}").list().orEmpty().any { it.endsWith(".tmp") })
    }

    @Test
    fun `a file changed outside the studio is external`() = runTest {
        files.write(Chat, "# Plan")
        File(root, "planning/${Chat.value}/plan.md").writeText("# Edited")
        assertEquals(PlanFileText("# Edited", isExternal = true), files.read(Chat))
        File(root, "planning/${Chat.value}/plan.sha256").delete()
        assertTrue(files.read(Chat)?.isExternal == true)
    }

    @Test
    fun `delete removes the chat directory only`() = runTest {
        files.write(Chat, "# One")
        files.write(PlanChat("other"), "# Two")
        files.delete(Chat)
        assertNull(files.read(Chat))
        assertFalse(File(root, "planning/${Chat.value}").exists())
        assertEquals("# Two", files.read(PlanChat("other"))?.text)
    }

    private class Stores(private val root: File) : DataStores {
        override val owner: StorageOwner = StorageOwner.App
        override fun filesDirectory(name: String): String = File(root, name).absolutePath
        override fun keyValue(spec: KeyValueSpec): KeyValueStore = error("unused")
        override fun <T : RoomDatabase> database(spec: DatabaseSpec<T>): T = error("unused")
        override suspend fun fire(event: DataEvent) = Unit
    }

    private object Unconfined : DispatcherProvider {
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
    }

    private companion object {
        val Chat = PlanChat("chat-1")
    }
}
