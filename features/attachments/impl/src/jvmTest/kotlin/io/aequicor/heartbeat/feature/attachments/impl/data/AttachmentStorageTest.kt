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
import io.aequicor.heartbeat.feature.attachments.api.AttachmentInput
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        override fun filesDirectory(name: String): String = File(directory, "files/$name").absolutePath
        override fun keyValue(spec: KeyValueSpec): KeyValueStore = error("Not used")

        @Suppress("UNCHECKED_CAST")
        override fun <T : RoomDatabase> database(spec: DatabaseSpec<T>): T {
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
