// `FileSystem.SYSTEM` is a member on JVM but an extension on native: the import is required for iOS,
// while detekt's JVM type resolution reports it as unused.
@file:Suppress("UnusedImport")

package io.aequicor.heartbeat.core.datastore.impl

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.RoomDatabase
import androidx.room.useReaderConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.datastore.DataEvent
import io.aequicor.heartbeat.core.datastore.DatabaseSpec
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.StorageOwner
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import okio.SYSTEM
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * App-wide registry of storages: the only place that creates DataStore and Room instances.
 *
 * - Preferences files: one `DataStore` per file for the whole process (DataStore fails on a second instance of an
 *   active file), each with its own job under the app scope, so a profile can be reopened and wiped safely.
 * - Owners ([OwnerStores]) attach with their scope and detach when it closes; [fire] reaches the open ones,
 *   the closed ones read the journals on open.
 */
@OptIn(ExperimentalAtomicApi::class)
@SingleIn(AppScope::class)
@Inject
internal class StoreRegistry(
    private val layout: StorageLayout,
    private val clock: RetentionClock,
    private val dispatchers: DispatcherProvider,
    private val roomBuilders: RoomBuilderFactory,
    @ForScope(AppScope::class) private val appScope: ScopeHandle,
) {
    private val log = Log.tag(DS_LOG_TAG)
    private val fileSystem = FileSystem.SYSTEM
    private val preferenceFiles = ConcurrentCache<String, PreferencesFile>()
    private val journals = ConcurrentCache<String, EventJournal>()
    private val owners = AtomicReference<List<OwnerStores>>(emptyList())

    /** Profiles being wiped: they cannot be attached until the wipe finishes. */
    private val wiping = AtomicReference<Set<ProfileId>>(emptySet())

    /** Storages of [owner], closed with [scope]. */
    fun attach(owner: StorageOwner, scope: ScopeHandle): OwnerStores {
        val stores = OwnerStores(owner, scope, this)
        update { current ->
            check(owner !is StorageOwner.Profile || owner.id !in wiping.load()) { "${owner.label} is being wiped" }
            current + stores
        }
        scope.onClose {
            update { it - stores }
            stores.close()
            log.i { "${owner.label}: storages closed" }
        }
        log.d { "${owner.label}: storages attached to scope ${scope.name}" }
        return stores
    }

    fun openKeyValue(owner: StorageOwner, spec: KeyValueSpec, scope: ScopeHandle): LoggingKeyValueStore {
        val label = "${owner.label}/$spec"
        val store = LoggingKeyValueStore(
            spec = spec,
            label = label,
            dataStore = preferences(layout.keyValueFile(owner, spec.name), label),
            clock = clock,
            journal = { withContext(dispatchers.io) { firedEvents(owner) } },
            scope = scope,
        )
        scope.coroutineScope.launch(dispatchers.io + CoroutineName("retention $label")) {
            store.prepare()
            runRetentionTimer(label, clock, store.nextDeadline()) { store.purgeExpired() }
        }
        log.i { "$label: opened" }
        return store
    }

    fun openDatabase(owner: StorageOwner, spec: DatabaseSpec<*>, scope: ScopeHandle): OpenDatabase {
        val label = "${owner.label}/$spec"
        val retention = DatabaseRetention(label, clock) { firedEvents(owner) }

        @Suppress("SpreadOperator") // Room takes migrations as varargs; once per database open
        val db = roomBuilders.builder(layout.databaseFile(owner, spec.name).toString(), spec.factory)
            .setDriver(DirectoryCreatingDriver(BundledSQLiteDriver(), fileSystem))
            .setQueryCoroutineContext(dispatchers.io)
            .addMigrations(*spec.migrations.map { LoggingMigration(label, it) }.toTypedArray())
            .addCallback(retention)
            .build()
        scope.coroutineScope.launch(dispatchers.io + CoroutineName("retention $label")) {
            db.useReaderConnection { } // opens the database: onOpen purges and finds the retention tables
            runRetentionTimer(label, clock, retention.nextDeadline(db)) { retention.purgeExpired(db) }
        }
        Log.tag(DB_LOG_TAG).i { "$label: opening" }
        return OpenDatabase(label, spec, db, retention)
    }

    /** Records [event] in the journal of [owner] and deletes its records from the affected open storages. */
    suspend fun fire(owner: StorageOwner, event: DataEvent) {
        val firedAt = clock.now()
        withContext(dispatchers.io) { journal(owner).record(event, firedAt) }
        val affected = owners.load().filter { owner == StorageOwner.App || it.owner == owner }
        log.i { "${owner.label}: $event fired, ${affected.size} open owners affected" }
        affected.forEach { it.purgeEvent(event.name, firedAt) }
    }

    /** Deletes every file of the profile [id]; its storages must be closed and stay closed during the wipe. */
    suspend fun wipeProfile(id: ProfileId) {
        val owner = StorageOwner.Profile(id)
        while (true) {
            val current = wiping.load()
            check(id !in current) { "${owner.label} is already being wiped" }
            if (wiping.compareAndSet(current, current + id)) break
        }
        try {
            // a new list instance: an attach that read `wiping` before the id was added fails its CAS and re-checks
            update { current ->
                check(current.none { it.owner == owner }) { "storages of ${owner.label} are open" }
                current.toList()
            }
            // the directory itself plus a separator: "alice" must not match the directory of "alice2"
            val dir = layout.profileDir(id).toString() + Path.DIRECTORY_SEPARATOR
            val inside = { path: String -> path.startsWith(dir) }
            preferenceFiles.removeAll(inside).forEach { it.job.cancelAndJoin() }
            journals.removeAll(inside)
            withContext(dispatchers.io) { fileSystem.deleteRecursively(layout.profileDir(id)) }
            log.i { "${owner.label}: storages wiped" }
        } finally {
            while (true) {
                val current = wiping.load()
                if (wiping.compareAndSet(current, current - id)) break
            }
        }
    }

    private fun firedEvents(owner: StorageOwner): Map<String, Long> {
        val own = journal(owner).read()
        if (owner == StorageOwner.App) return own
        val app = journal(StorageOwner.App).read()
        return (app.keys + own.keys).associateWith { maxOf(app[it] ?: Long.MIN_VALUE, own[it] ?: Long.MIN_VALUE) }
    }

    private fun journal(owner: StorageOwner): EventJournal {
        val file = layout.journalFile(owner)
        return journals.getOrPut(file.toString()) { EventJournal(file, fileSystem) }
    }

    private fun preferences(file: Path, label: String): DataStore<Preferences> =
        preferenceFiles.getOrPut(file.toString()) {
            val job = SupervisorJob(appScope.coroutineScope.coroutineContext[Job])
            val dataStore = PreferenceDataStoreFactory.createWithPath(
                corruptionHandler = ReplaceFileCorruptionHandler { error ->
                    log.e(error) { "$label: file is corrupted, replaced with empty data" }
                    emptyPreferences()
                },
                scope = CoroutineScope(job + dispatchers.io + CoroutineName("datastore $label")),
                produceFile = {
                    file.parent?.let(fileSystem::createDirectories)
                    file
                },
            )
            PreferencesFile(dataStore, job)
        }.dataStore

    private inline fun update(transform: (List<OwnerStores>) -> List<OwnerStores>) {
        while (true) {
            val current = owners.load()
            if (owners.compareAndSet(current, transform(current))) return
        }
    }

    private data class PreferencesFile(val dataStore: DataStore<Preferences>, val job: Job)
}

/** A database opened by [StoreRegistry]. */
internal data class OpenDatabase(
    val label: String,
    val spec: DatabaseSpec<*>,
    val db: RoomDatabase,
    val retention: DatabaseRetention,
)
