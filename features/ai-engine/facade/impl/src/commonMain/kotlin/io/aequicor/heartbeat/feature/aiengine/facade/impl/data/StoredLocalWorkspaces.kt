package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.stringKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspace
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

internal val LocalWorkspacesSpec = KeyValueSpec("aiengine_local_workspaces")
private val ProjectsKey = stringKey("projects")

/** Canonical paths are resolved by a platform primitive; no filesystem access happens on the UI dispatcher. */
internal interface WorkspaceDirectories {
    val isAvailable: Boolean
    suspend fun canonical(directory: String): WorkspaceDirectory?
}

internal data class WorkspaceDirectory(val path: String, val name: String) {
    override fun toString(): String = "WorkspaceDirectory"
}

/** Profile registry; corrupt metadata fails closed and is never replaced with an empty project list. */
@Inject
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
internal class StoredLocalWorkspaces(
    @ForScope(ProfileScope::class) stores: DataStores,
    private val directories: WorkspaceDirectories,
) : LocalWorkspaces {
    private val log = Log.tag("LocalWorkspaces")
    private val store = stores.keyValue(LocalWorkspacesSpec)
    private val mutex = Mutex()
    override val isAvailable: Boolean get() = directories.isAvailable

    override fun observe(): Flow<List<LocalWorkspace>> {
        if (!isAvailable) return flowOf(emptyList())
        log.d { "Observing local projects" }
        return store.observe(ProjectsKey).map { raw ->
            decode(raw).filterNot { it.isManaged }.map { it.metadata() }
        }.distinctUntilChanged()
    }

    override suspend fun register(directory: String): LocalWorkspace = register(directory, isManaged = false)

    override suspend fun registerManaged(directory: String): LocalWorkspace = register(directory, isManaged = true)

    private suspend fun register(directory: String, isManaged: Boolean): LocalWorkspace = mutex.withLock {
        requireAvailable()
        val canonical = requireNotNull(directories.canonical(directory)) { "Local project directory is unavailable" }
        val saved = decode(store.get(ProjectsKey))
        saved.firstOrNull { it.directory == canonical.path }?.let { existing ->
            if (!isManaged && existing.isManaged) {
                store.set(
                    ProjectsKey,
                    Json.encodeToString(saved.map { if (it.id == existing.id) it.copy(isManaged = false) else it }),
                )
            }
            return@withLock existing.metadata()
        }
        val entry = WorkspaceEntry(Uuid.random().toString(), canonical.name, canonical.path, isManaged)
        log.i { "Registering local project" }
        store.set(ProjectsKey, Json.encodeToString(saved + entry))
        entry.metadata()
    }

    override suspend fun resolve(ref: WorkspaceRef): String? {
        requireAvailable()
        log.v { "Resolving local project" }
        val entry = decode(store.get(ProjectsKey)).firstOrNull { it.id == ref.value }
        // Do not silently redirect an existing project after its directory was replaced by a symlink.
        return entry?.let { directories.canonical(it.directory)?.path?.takeIf { path -> path == it.directory } }
    }

    private fun requireAvailable() {
        if (!isAvailable) throw UnsupportedOperationException("Local projects are unavailable on this platform")
    }

    private fun decode(raw: String?): List<WorkspaceEntry> = try {
        if (raw == null) {
            emptyList()
        } else {
            Json.decodeFromString<List<WorkspaceEntry>>(raw).also { entries ->
                check(entries.all { it.id.isNotBlank() && it.name.isNotBlank() && it.directory.isNotBlank() })
                check(entries.map { it.id }.distinct().size == entries.size)
                check(entries.map { it.directory }.distinct().size == entries.size)
            }
        }
    } catch (error: SerializationException) {
        throw error.safeWorkspaceFailure()
    }
}

/** Discards path-bearing messages, serialized data and causes before an error reaches application logs. */
internal fun Exception.safeWorkspaceFailure(): IllegalStateException =
    IllegalStateException("Local project operation failed (${this::class.simpleName.orEmpty()})")

@Serializable
private data class WorkspaceEntry(
    val id: String,
    val name: String,
    val directory: String,
    @SerialName("managed") val isManaged: Boolean = false,
) {
    fun metadata(): LocalWorkspace = LocalWorkspace(WorkspaceRef(id), name)
    override fun toString(): String = "WorkspaceEntry"
}
