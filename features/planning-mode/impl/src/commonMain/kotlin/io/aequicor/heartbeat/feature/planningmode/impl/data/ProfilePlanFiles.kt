package io.aequicor.heartbeat.feature.planningmode.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.planningmode.api.PlanChat
import io.aequicor.heartbeat.feature.planningmode.impl.domain.PlanFileText
import io.aequicor.heartbeat.feature.planningmode.impl.domain.PlanFiles
import kotlinx.coroutines.withContext
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * Plan texts in `<profile files>/planning/<chat>/plan.md`, next to the digest of what the studio last wrote
 * (`plan.sha256`): a file whose digest differs was edited outside the studio. Writes go through a temporary file and
 * an atomic move, so a reader never sees half a plan. Chat ids are validated by [PlanChat] and cannot leave the
 * directory. Texts are user data and never logged.
 */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
@Inject
internal class ProfilePlanFiles(
    @ForScope(ProfileScope::class) private val stores: DataStores,
    private val dispatchers: DispatcherProvider,
    private val fs: FileSystem = FileSystem.SYSTEM,
) : PlanFiles {
    private val root: Path get() = stores.filesDirectory(DIRECTORY).toPath()

    override suspend fun read(chat: PlanChat): PlanFileText? = withContext(dispatchers.io) {
        val directory = root / chat.value
        val file = directory / PLAN_FILE
        if (!fs.exists(file)) return@withContext null
        val text = fs.read(file) { readUtf8() }
        val digest = (directory / DIGEST_FILE).takeIf(fs::exists)?.let { fs.read(it) { readUtf8() }.trim() }
        PlanFileText(text, isExternal = digest != text.digest())
    }

    override suspend fun write(chat: PlanChat, text: String): Unit = withContext(dispatchers.io) {
        val directory = root / chat.value
        fs.createDirectories(directory)
        replace(directory / PLAN_FILE, text)
        replace(directory / DIGEST_FILE, text.digest())
    }

    override suspend fun delete(chat: PlanChat): Unit = withContext(dispatchers.io) {
        fs.deleteRecursively(root / chat.value)
    }

    private fun replace(target: Path, text: String) {
        val temporary = requireNotNull(target.parent) / ".${target.name}.tmp"
        fs.write(temporary) { writeUtf8(text) }
        fs.atomicMove(temporary, target)
    }

    private fun String.digest(): String = encodeUtf8().sha256().hex()

    private companion object {
        const val DIRECTORY = "planning"
        const val PLAN_FILE = "plan.md"
        const val DIGEST_FILE = "plan.sha256"
    }
}
