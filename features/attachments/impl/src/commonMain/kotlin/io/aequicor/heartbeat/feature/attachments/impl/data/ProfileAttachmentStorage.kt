package io.aequicor.heartbeat.feature.attachments.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptInputSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedResource
import io.aequicor.heartbeat.feature.attachments.api.AttachmentDescriptor
import io.aequicor.heartbeat.feature.attachments.api.AttachmentFailure
import io.aequicor.heartbeat.feature.attachments.api.AttachmentId
import io.aequicor.heartbeat.feature.attachments.api.AttachmentInput
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsCatalog
import io.aequicor.heartbeat.feature.attachments.impl.domain.AttachmentMetadata
import io.aequicor.heartbeat.feature.attachments.impl.domain.AttachmentStorage
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import kotlin.uuid.Uuid

/** An explicitly safe validation error, suitable for machine output. */
internal class AttachmentValidationException(val failure: AttachmentFailure) : IllegalArgumentException(failure.name)

@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class, binding = binding<AttachmentStorage>())
@ContributesBinding(ProfileScope::class, binding = binding<AttachmentMetadata>())
@Inject
internal class ProfileAttachmentStorage(
    @ForScope(ProfileScope::class) private val stores: DataStores,
    private val dispatchers: DispatcherProvider,
    private val fs: FileSystem = FileSystem.SYSTEM,
) : AttachmentStorage,
    AttachmentMetadata {
    private val log = Log.tag("AttachmentsStorage")
    private val dao by lazy { stores.database(AttachmentDatabaseSpec).attachments() }
    private val directory get() = stores.filesDirectory("attachments").toPath()

    override suspend fun prepare(): Unit = withContext(dispatchers.io) {
        log.d { "Prepare profile attachment storage" }
        fs.createDirectories(directory)
        dao.get("")
        Unit
    }

    override suspend fun import(
        inputs: List<AttachmentInput>,
        support: PromptInputSupport,
        deduplicationKey: String?,
    ): List<AttachmentDescriptor> = withContext(dispatchers.io) {
        log.d { "Import attachments count=${inputs.size}" }
        if (inputs.isEmpty()) throw AttachmentValidationException(AttachmentFailure.Missing)
        if (inputs.size > minOf(support.maxAttachments, MAX_ATTACHMENTS)) {
            throw AttachmentValidationException(AttachmentFailure.TooMany)
        }
        if (deduplicationKey != null) {
            require(inputs.size == 1 && deduplicationKey.length <= MAX_NAME_LENGTH) { "Invalid migration identity" }
            dao.byDeduplicationKey(deduplicationKey)?.let { return@withContext listOf(it.descriptor()) }
        }
        val maxBytes = minOf(support.maxFileBytes, MAX_FILE_BYTES)
        var totalBytes = 0L
        val prepared = inputs.map { input ->
            prepareInput(input, support, maxBytes).also {
                totalBytes += it.bytes.size
                if (totalBytes > minOf(support.maxTotalBytes, MAX_TOTAL_BYTES)) {
                    throw AttachmentValidationException(AttachmentFailure.TooLarge)
                }
            }
        }
        fs.createDirectories(directory)
        persistInputs(prepared, deduplicationKey)
    }

    /** Rolls back unpublished files; cancellation after the atomic Room commit keeps durable references intact. */
    private suspend fun persistInputs(
        inputs: List<AttachmentInput.Bytes>,
        deduplicationKey: String?,
    ): List<AttachmentDescriptor> {
        val rows = inputs.map {
            AttachmentEntity(Uuid.random().toString(), it.name, it.mediaType, it.bytes.size.toLong(), deduplicationKey)
        }
        // Resolve once so rollback also works if the profile closes during an import.
        val ownedDirectory = directory
        var isCommitted = false
        try {
            rows.zip(inputs).forEach { (row, input) ->
                currentCoroutineContext().ensureActive()
                writeAttachment(ownedDirectory, row, input.bytes)
            }
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                dao.insert(rows)
                isCommitted = true
            }
            return rows.map(AttachmentEntity::descriptor)
        } finally {
            if (!isCommitted) {
                withContext(NonCancellable) {
                    log.d { "Roll back unpublished attachments count=${rows.size}" }
                    rows.forEach { removeOwnedFile(ownedDirectory / "${it.id}.${attachmentExtension(it.mediaType)}") }
                }
            }
        }
    }

    private fun writeAttachment(ownedDirectory: Path, row: AttachmentEntity, bytes: ByteArray) {
        val temporary = ownedDirectory / "${row.id}.tmp"
        val target = ownedDirectory / "${row.id}.${attachmentExtension(row.mediaType)}"
        try {
            fs.openReadWrite(temporary, mustCreate = true).use { handle ->
                handle.sink().buffer().use { it.write(bytes) }
                handle.flush()
            }
            fs.atomicMove(temporary, target)
        } finally {
            removeOwnedFile(temporary)
        }
    }

    private fun removeOwnedFile(path: Path) {
        try {
            fs.delete(path, mustExist = false)
        } catch (error: IOException) {
            log.w(error) { "Failed to remove unpublished attachment file" }
        }
    }

    override suspend fun read(id: AttachmentId): ResolvedResource = withContext(dispatchers.io) {
        log.d { "Read saved attachment" }
        val row = dao.get(id.value) ?: throw AttachmentValidationException(AttachmentFailure.Missing)
        require(row.id.matches(IDENTIFIER)) { "Invalid owned attachment identifier" }
        val path = directory / "${row.id}.${attachmentExtension(row.mediaType)}"
        if (!fs.exists(path)) throw AttachmentValidationException(AttachmentFailure.Missing)
        val bytes = readBounded(path.toString(), minOf(row.sizeBytes, MAX_FILE_BYTES))
        if (bytes.size.toLong() != row.sizeBytes) throw AttachmentValidationException(AttachmentFailure.Missing)
        ResolvedResource(row.name, row.mediaType, bytes, path.toString())
    }

    override suspend fun get(id: AttachmentId): AttachmentDescriptor? = withContext(dispatchers.io) {
        log.d { "Read attachment metadata" }
        dao.get(id.value)?.descriptor()
    }

    /**
     * Routine projection a caller re-creates on every transcript revision: an empty request is served without
     * storage, and a real one is traced per collection instead of per construction, so a streamed turn does not
     * fill the console with identical reads.
     */
    override fun observe(ids: List<AttachmentId>): Flow<List<AttachmentDescriptor>> {
        if (ids.isEmpty()) return flowOf(emptyList())
        return dao.observe(ids.map { it.value })
            .onStart { log.v { "Observe attachment metadata count=${ids.size}" } }
            .map { rows ->
                val byId = rows.associateBy { it.id }
                ids.mapNotNull { byId[it.value]?.descriptor() }
            }
    }

    private fun prepareInput(
        input: AttachmentInput,
        support: PromptInputSupport,
        maxBytes: Long,
    ): AttachmentInput.Bytes {
        val prepared = when (input) {
            is AttachmentInput.Bytes -> input

            is AttachmentInput.File -> {
                val name = input.name ?: input.location.toPath().name
                val mime = input.mediaType ?: attachmentMediaType(name)
                AttachmentInput.Bytes(name, mime, readBounded(input.location, maxBytes))
            }
        }
        val failure = when {
            !support.accepts(prepared.mediaType) -> AttachmentFailure.Unsupported
            prepared.bytes.isEmpty() -> AttachmentFailure.Missing
            prepared.bytes.size > maxBytes -> AttachmentFailure.TooLarge
            else -> null
        }
        if (failure != null) throw AttachmentValidationException(failure)
        validateAttachmentContent(prepared.mediaType, prepared.bytes)
        val name = prepared.name.substringAfterLast(
            '/',
        ).substringAfterLast('\\').replace('\u0000', '_').take(MAX_NAME_LENGTH)
        return prepared.copy(
            name = name.takeUnless { it.isBlank() || it in setOf(".", "..") }
                ?: "attachment.${attachmentExtension(prepared.mediaType)}",
        )
    }

    private fun readBounded(location: String, maxBytes: Long): ByteArray {
        val path = location.toPath()
        if (!fs.exists(path)) throw AttachmentValidationException(AttachmentFailure.Missing)
        return fs.source(path).buffer().use { source ->
            source.request(maxBytes + 1)
            val bytes = source.readByteArray(minOf(source.buffer.size, maxBytes + 1))
            if (bytes.size > maxBytes) throw AttachmentValidationException(AttachmentFailure.TooLarge)
            bytes
        }
    }

    private companion object {
        const val MAX_NAME_LENGTH = 255
        const val MAX_FILE_BYTES = 10L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 25L * 1024 * 1024
        const val MAX_ATTACHMENTS = 10
        val IDENTIFIER = Regex("[a-f0-9-]{36}")
    }
}

@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
@Inject
internal class ProfileAttachmentsCatalog(private val storage: AttachmentMetadata) : AttachmentsCatalog {
    override suspend fun get(id: AttachmentId): AttachmentDescriptor? = storage.get(id)
    override fun observe(ids: List<AttachmentId>): Flow<List<AttachmentDescriptor>> = storage.observe(ids)
}

private fun AttachmentEntity.descriptor(): AttachmentDescriptor = AttachmentDescriptor(
    AttachmentId(id),
    name,
    mediaType,
    sizeBytes,
)

/** Extension-based picker hint; imported bytes still pass model support and bounded size checks. */
internal fun attachmentMediaType(name: String): String = when (name.substringAfterLast('.').lowercase()) {
    "txt" -> "text/plain"
    "md", "markdown" -> "text/markdown"
    "pdf" -> "application/pdf"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    else -> "application/octet-stream"
}

/** Safe filename suffix needed by native applications; never derived from an external path. */
internal fun attachmentExtension(mime: String): String = when (mime) {
    "text/plain" -> "txt"
    "text/markdown" -> "md"
    "application/pdf" -> "pdf"
    "image/png" -> "png"
    "image/jpeg" -> "jpg"
    "image/gif" -> "gif"
    "image/webp" -> "webp"
    else -> "bin"
}

/** Rejects a renamed binary input before it can be sent with a misleading MIME type. */
internal fun validateAttachmentContent(mime: String, bytes: ByteArray) {
    fun prefix(vararg expected: Int): Boolean = bytes.size >= expected.size &&
        expected.indices.all { bytes[it].toInt() and BYTE_MASK == expected[it] }
    val isValid = when (mime) {
        "image/png" -> prefix(137, 80, 78, 71, 13, 10, 26, 10)

        "image/jpeg" -> prefix(255, 216, 255)

        "image/gif" -> bytes.take(6).toByteArray().decodeToString() in setOf("GIF87a", "GIF89a")

        "image/webp" -> bytes.size >= 12 && bytes.copyOfRange(0, 4).decodeToString() == "RIFF" &&
            bytes.copyOfRange(8, 12).decodeToString() == "WEBP"

        "application/pdf" -> prefix(37, 80, 68, 70, 45)

        "text/plain", "text/markdown" -> bytes.decodeToString().encodeToByteArray().contentEquals(bytes)

        else -> false
    }
    if (!isValid) throw AttachmentValidationException(AttachmentFailure.Unsupported)
}

private const val BYTE_MASK = 255
