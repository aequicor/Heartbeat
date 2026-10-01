package io.aequicor.heartbeat.feature.attachments.impl.data

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.datastore.DataEvent
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.DatabaseSpec
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.core.datastore.StorageOwner
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptInputSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.attachments.api.AttachmentFailure
import io.aequicor.heartbeat.feature.attachments.api.AttachmentId
import io.aequicor.heartbeat.feature.attachments.api.AttachmentInput
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AttachmentStorageTest {
    private val root = Files.createTempDirectory("attachments-test").toFile()
    private val stores = mutableListOf<TestStores>()
    private val support = PromptInputSupport.TextDocuments

    @AfterTest
    fun cleanup() {
        stores.forEach { it.close() }
        root.deleteRecursively()
    }

    @Test
    fun `imported copy survives deleting original and reopening profile database`() = runTest {
        val dispatchers = TestDispatchers(UnconfinedTestDispatcher(testScheduler))
        val original = File(root, "original.md").apply { writeText("Durable user document") }
        val firstStores = owner("alice")
        val storage = ProfileAttachmentStorage(firstStores, dispatchers)
        val descriptor = storage.import(listOf(AttachmentInput.File(original.absolutePath)), support, null).single()
        original.delete()
        firstStores.close()

        val reopened = ProfileAttachmentStorage(owner("alice"), dispatchers)
        assertEquals(descriptor, reopened.get(descriptor.id))
        assertContentEquals("Durable user document".encodeToByteArray(), reopened.read(descriptor.id).bytes)
        assertEquals(listOf(descriptor), reopened.observe(listOf(descriptor.id)).first())
        assertTrue(reopened.read(descriptor.id).localPath.orEmpty().endsWith(".md"))
    }

    @Test
    fun `same identifiers cannot resolve in a different profile or as arbitrary paths`() = runTest {
        val dispatchers = TestDispatchers(UnconfinedTestDispatcher(testScheduler))
        val alice = ProfileAttachmentStorage(owner("alice"), dispatchers)
        val bob = ProfileAttachmentStorage(owner("bob"), dispatchers)
        val descriptor = alice.import(
            listOf(AttachmentInput.Bytes("notes.txt", "text/plain", byteArrayOf(65))),
            support,
            null,
        ).single()
        assertNull(bob.get(descriptor.id))
        assertFailsWith<AttachmentValidationException> { bob.read(descriptor.id) }
        assertNull(ProfileAttachmentResolver(alice).resolve(ResourceRef("file:/etc/passwd", "text/plain")))
        assertFailsWith<AttachmentValidationException> {
            ProfileAttachmentResolver(alice).resolve(ResourceRef("attachment:../../passwd", "text/plain"))
        }
    }

    @Test
    fun `migration identity is idempotent even if old input is no longer readable`() = runTest {
        val storage = ProfileAttachmentStorage(owner("alice"), TestDispatchers(UnconfinedTestDispatcher(testScheduler)))
        val first = storage.import(
            listOf(AttachmentInput.Bytes("notes.txt", "text/plain", byteArrayOf(65))),
            support,
            "research:1",
        )
        val repeated = storage.import(listOf(AttachmentInput.File("/nonexistent.txt")), support, "research:1")
        assertEquals(first, repeated)
    }

    @Test
    fun `failed second file write rolls back the batch and keeps previously published bytes`() = runTest {
        val owner = owner("alice")
        val dispatchers = TestDispatchers(UnconfinedTestDispatcher(testScheduler))
        val storage = ProfileAttachmentStorage(owner, dispatchers)
        val input = AttachmentInput.Bytes("notes.txt", "text/plain", byteArrayOf(65))
        val saved = storage.import(listOf(input), support, null).single()
        var moves = 0
        val failing = object : ForwardingFileSystem(FileSystem.SYSTEM) {
            override fun atomicMove(source: Path, target: Path) {
                if (++moves == 2) throw IOException("Injected second copy failure")
                super.atomicMove(source, target)
            }
        }
        assertFailsWith<IOException> {
            ProfileAttachmentStorage(owner, dispatchers, failing).import(listOf(input, input), support, null)
        }
        assertEquals(listOf("${saved.id.value}.txt"), File(owner.filesDirectory("attachments")).list()?.toList())
        assertContentEquals(input.bytes, storage.read(saved.id).bytes)
    }

    @Test
    fun `failed metadata write removes only unpublished files`() = runTest {
        val owner = owner("alice")
        val dispatchers = TestDispatchers(UnconfinedTestDispatcher(testScheduler))
        val storage = ProfileAttachmentStorage(owner, dispatchers)
        val input = AttachmentInput.Bytes("notes.txt", "text/plain", byteArrayOf(65))
        val saved = storage.import(listOf(input), support, null).single()
        owner.close()
        assertFails { storage.import(listOf(input), support, null) }
        assertEquals(listOf("${saved.id.value}.txt"), File(owner.filesDirectory("attachments")).list()?.toList())
        assertContentEquals(input.bytes, ProfileAttachmentStorage(owner, dispatchers).read(saved.id).bytes)
    }

    @Test
    fun `cancellation after copying a file rolls back unpublished bytes`() = runTest {
        val owner = owner("alice")
        lateinit var importing: Job
        val cancelling = object : ForwardingFileSystem(FileSystem.SYSTEM) {
            override fun atomicMove(source: Path, target: Path) {
                super.atomicMove(source, target)
                importing.cancel()
            }
        }
        val storage = ProfileAttachmentStorage(
            owner,
            TestDispatchers(UnconfinedTestDispatcher(testScheduler)),
            cancelling,
        )
        importing = launch {
            storage.import(listOf(AttachmentInput.Bytes("notes.txt", "text/plain", byteArrayOf(65))), support, null)
        }
        runCurrent()
        importing.join()
        assertTrue(importing.isCancelled)
        assertTrue(File(owner.filesDirectory("attachments")).listFiles().isNullOrEmpty())
    }

    @Test
    fun `cancellation during metadata commit retains published references and original bytes`() = runTest {
        val owner = owner("alice")
        lateinit var importing: Job
        owner.onDatabase = { importing.cancel() }
        val storage = ProfileAttachmentStorage(owner, TestDispatchers(UnconfinedTestDispatcher(testScheduler)))
        val input = AttachmentInput.Bytes("notes.txt", "text/plain", byteArrayOf(65))
        importing = launch { storage.import(listOf(input), support, null) }
        runCurrent()
        importing.join()
        assertTrue(importing.isCancelled)
        val file = requireNotNull(File(owner.filesDirectory("attachments")).listFiles()).single()
        val id = AttachmentId(file.nameWithoutExtension)
        assertEquals(id, storage.get(id)?.id)
        assertContentEquals(input.bytes, storage.read(id).bytes)
    }

    @Test
    fun `limits and malformed MIME are rejected before any metadata is written`() = runTest {
        val storage = ProfileAttachmentStorage(owner("alice"), TestDispatchers(UnconfinedTestDispatcher(testScheduler)))
        val small = support.copy(maxFileBytes = 2, maxTotalBytes = 3, maxAttachments = 2)
        val input = AttachmentInput.Bytes("notes.txt", "text/plain", byteArrayOf(65, 66))
        assertEquals(
            AttachmentFailure.TooLarge,
            assertFailsWith<AttachmentValidationException> {
                storage.import(listOf(input.copy(bytes = byteArrayOf(65, 66, 67))), small, null)
            }.failure,
        )
        assertEquals(
            AttachmentFailure.TooLarge,
            assertFailsWith<AttachmentValidationException> {
                storage.import(listOf(input, input), small, null)
            }.failure,
        )
        assertEquals(
            AttachmentFailure.TooMany,
            assertFailsWith<AttachmentValidationException> {
                storage.import(listOf(input, input, input), small, null)
            }.failure,
        )
        assertEquals(
            AttachmentFailure.Unsupported,
            assertFailsWith<AttachmentValidationException> {
                storage.import(
                    listOf(AttachmentInput.Bytes("photo.png", "image/png", byteArrayOf(65))),
                    support.copy(imageMediaTypes = setOf("image/png")),
                    null,
                )
            }.failure,
        )
        assertTrue(File(root, "alice/files/attachments").listFiles().isNullOrEmpty())
    }

    @Test
    fun `missing owned bytes and tampered MIME do not silently resolve`() = runTest {
        val storage = ProfileAttachmentStorage(owner("alice"), TestDispatchers(UnconfinedTestDispatcher(testScheduler)))
        val item = storage.import(
            listOf(AttachmentInput.Bytes("notes.txt", "text/plain", byteArrayOf(65))),
            support,
            null,
        ).single()
        assertFailsWith<IllegalArgumentException> {
            ProfileAttachmentResolver(
                storage,
            ).resolve(item.resource.copy(mediaType = "application/pdf"))
        }
        File(requireNotNull(storage.read(item.id).localPath)).delete()
        assertEquals(
            AttachmentFailure.Missing,
            assertFailsWith<AttachmentValidationException> { storage.read(item.id) }.failure,
        )
    }

    private fun owner(id: String): TestStores = TestStores(File(root, id), id).also(stores::add)

    private class TestStores(private val directory: File, id: String) : DataStores {
        override val owner: StorageOwner = StorageOwner.Profile(ProfileId(id))
        private var database: AttachmentDatabase? = null
        var onDatabase: () -> Unit = {}
        override fun filesDirectory(name: String): String = File(directory, "files/$name").absolutePath
        override fun keyValue(spec: KeyValueSpec): KeyValueStore = error("Not used")

        @Suppress("UNCHECKED_CAST")
        override fun <T : RoomDatabase> database(spec: DatabaseSpec<T>): T {
            onDatabase()
            directory.mkdirs()
            return (
                database ?: Room.databaseBuilder<AttachmentDatabase>(File(directory, "attachments.db").absolutePath)
                    .setDriver(BundledSQLiteDriver()).build().also { database = it }
            ) as T
        }
        override suspend fun fire(event: DataEvent) = Unit
        fun close() {
            database?.close()
            database = null
        }
    }

    private class TestDispatchers(override val io: CoroutineDispatcher) : DispatcherProvider {
        override val main: CoroutineDispatcher get() = io
        override val default: CoroutineDispatcher get() = io
    }
}
