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
import io.aequicor.heartbeat.feature.planningmode.api.PlanLimits
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
        files.write(Chat, "# Plan\n\nПлан", 4)
        assertEquals(PlanFileText("# Plan\n\nПлан", isExternal = false, revision = 4), files.read(Chat))
        assertEquals("# Plan\n\nПлан", File(root, "planning/${Chat.value}/plan.md").readText())
        assertFalse(File(root, "planning/${Chat.value}").list().orEmpty().any { it.endsWith(".tmp") })
    }

    @Test
    fun `a file changed outside the studio is external`() = runTest {
        files.write(Chat, "# Plan", 1)
        File(root, "planning/${Chat.value}/plan.md").writeText("# Edited")
        assertEquals(PlanFileText("# Edited", isExternal = true, revision = 1), files.read(Chat))
        File(root, "planning/${Chat.value}/plan.sha256").delete()
        assertEquals(PlanFileText("# Edited", isExternal = true), files.read(Chat))
    }

    @Test
    fun `a reader between the stamp and the plan replacement sees the previous revision`() = runTest {
        files.write(Chat, "# One", 1)
        files.write(Chat, "# Two", 2)
        val plan = File(root, "planning/${Chat.value}/plan.md")
        plan.writeText("# One")
        assertEquals(PlanFileText("# One", isExternal = false, revision = 1), files.read(Chat))
        files.write(Chat, "# Three", 3)
        plan.writeText("# One")
        assertEquals(PlanFileText("# One", isExternal = false, revision = 1), files.read(Chat), "kept on disk")
        plan.writeText("# Two")
        assertEquals(PlanFileText("# Two", isExternal = true, revision = 3), files.read(Chat))
    }

    @Test
    fun `stamps of earlier versions are read`() = runTest {
        files.write(Chat, "# Plan", 1)
        val stamp = File(root, "planning/${Chat.value}/plan.sha256")
        val digest = stamp.readLines()[1]
        stamp.writeText("7\n$digest\n")
        assertEquals(PlanFileText("# Plan", isExternal = false, revision = 7), files.read(Chat))
        stamp.writeText(digest)
        assertEquals(PlanFileText("# Plan", isExternal = false), files.read(Chat))
        files.write(Chat, "# Next", 8)
        assertEquals(PlanFileText("# Next", isExternal = false, revision = 8), files.read(Chat))
    }

    @Test
    fun `a file too large to be a plan is not read`() = runTest {
        files.write(Chat, "# Plan", 2)
        File(root, "planning/${Chat.value}/plan.md").writeText("x".repeat(PlanLimits.MAX_CHARS * 4 + 1))
        assertEquals(PlanFileText("", isExternal = true, revision = 2, isOversized = true), files.read(Chat))
    }

    @Test
    fun `delete removes the chat directory only`() = runTest {
        files.write(Chat, "# One", 1)
        files.write(PlanChat("other"), "# Two", 1)
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
