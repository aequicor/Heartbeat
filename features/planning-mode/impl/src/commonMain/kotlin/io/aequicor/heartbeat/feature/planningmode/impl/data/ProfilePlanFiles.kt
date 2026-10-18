package io.aequicor.heartbeat.feature.planningmode.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.planningmode.api.PlanChat
import io.aequicor.heartbeat.feature.planningmode.api.PlanLimits
import io.aequicor.heartbeat.feature.planningmode.impl.domain.PlanFileText
import io.aequicor.heartbeat.feature.planningmode.impl.domain.PlanFiles
import kotlinx.coroutines.withContext
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * Plan texts in `<profile files>/planning/<chat>/plan.md`, next to the revisions and digests of what the studio wrote
 * (`plan.sha256`): a text matching neither was edited outside the studio. The stamp is replaced before the plan and
 * keeps the previous text's revision and digest, so a reader between the two replacements sees either text as the
 * studio's own; the revision lets a plan written just before an interrupted index save load with the revision it was
 * written as. Writes go through a temporary file and an atomic move, so a reader never sees half a plan. Files larger
 * than a plan can be are not read. Chat ids are validated by [PlanChat] and cannot leave the directory. Texts are
 * user data and never logged.
 */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
@Inject
internal class ProfilePlanFiles(
    @ForScope(ProfileScope::class) private val stores: DataStores,
    private val dispatchers: DispatcherProvider,
    private val fs: FileSystem = FileSystem.SYSTEM,
) : PlanFiles {
    private val log = Log.tag("ProfilePlanFiles")
    private val root: Path get() = stores.filesDirectory(DIRECTORY).toPath()

    override suspend fun read(chat: PlanChat): PlanFileText? = withContext(dispatchers.io) {
        val directory = root / chat.value
        val file = directory / PLAN_FILE
        if (!fs.exists(file)) return@withContext null
        val stamps = stamps(directory)
        val size = fs.metadata(file).size ?: 0
        if (size > MAX_FILE_BYTES) {
            log.w { "plan file too large, not read: chat=$chat bytes=$size" }
            return@withContext PlanFileText("", isExternal = true, stamps.firstOrNull()?.revision, isOversized = true)
        }
        val text = fs.read(file) { readUtf8() }
        val digest = text.digest()
        val own = stamps.firstOrNull { it.digest == digest }
        if (own != null) {
            PlanFileText(text, isExternal = false, own.revision)
        } else {
            PlanFileText(text, isExternal = true, stamps.firstOrNull()?.revision)
        }
    }

    override suspend fun write(chat: PlanChat, text: String, revision: Long): Unit = withContext(dispatchers.io) {
        val directory = root / chat.value
        val file = directory / PLAN_FILE
        fs.createDirectories(directory)
        val stamps = stamps(directory)
        val onDisk = file.takeIf(fs::exists)
            ?.takeIf { (fs.metadata(it).size ?: 0) <= MAX_FILE_BYTES }
            ?.let { fs.read(it) { readUtf8() }.digest() }
        val previous = stamps.firstOrNull { it.digest == onDisk } ?: stamps.firstOrNull()
        val stamp = listOfNotNull(Stamp(revision, text.digest()), previous)
            .joinToString("\n") { "${it.revision ?: UNKNOWN_REVISION}\n${it.digest}" }
        replace(directory / DIGEST_FILE, stamp)
        replace(file, text)
    }

    override suspend fun delete(chat: PlanChat): Unit = withContext(dispatchers.io) {
        fs.deleteRecursively(root / chat.value)
    }

    /**
     * The texts the studio wrote, newest first. Earlier versions kept one line (the digest) or two (revision and
     * digest); blank lines, such as a trailing newline added by an editor, are ignored.
     */
    private fun stamps(directory: Path): List<Stamp> {
        val file = directory / DIGEST_FILE
        if (!fs.exists(file)) return emptyList()
        val lines = fs.read(file) { readUtf8() }.lines().map(String::trim).filter(String::isNotEmpty)
        return when {
            lines.size == 1 -> listOf(Stamp(null, lines.single()))

            lines.size % STAMP_LINES == 0 && lines.size <= STAMP_LINES * MAX_STAMPS ->
                lines.chunked(STAMP_LINES) { (revision, digest) -> Stamp(revision.toLongOrNull(), digest) }

            else -> emptyList()
        }
    }

    private fun replace(target: Path, text: String) {
        val temporary = requireNotNull(target.parent) / ".${target.name}.tmp"
        fs.write(temporary) { writeUtf8(text) }
        fs.atomicMove(temporary, target)
    }

    private fun String.digest(): String = encodeUtf8().sha256().hex()

    /** A text the studio wrote: its [digest] and the [revision] it was written as, unknown for old files. */
    private data class Stamp(val revision: Long?, val digest: String)

    private companion object {
        const val DIRECTORY = "planning"
        const val PLAN_FILE = "plan.md"
        const val DIGEST_FILE = "plan.sha256"
        const val UNKNOWN_REVISION = "-"

        /** A stamp line pair: revision and digest. */
        const val STAMP_LINES = 2

        /** The current text and the previous one. */
        const val MAX_STAMPS = 2

        /** A UTF-8 character takes at most four bytes; a larger file cannot hold a plan of [PlanLimits.MAX_CHARS]. */
        const val MAX_FILE_BYTES = PlanLimits.MAX_CHARS * 4L
    }
}
