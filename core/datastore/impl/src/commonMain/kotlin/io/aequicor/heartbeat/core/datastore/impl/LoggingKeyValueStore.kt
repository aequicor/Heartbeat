package io.aequicor.heartbeat.core.datastore.impl

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.core.datastore.Retention
import io.aequicor.heartbeat.core.datastore.StoreKey
import io.aequicor.heartbeat.core.datastore.StoreValueType
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okio.IOException
import kotlin.concurrent.Volatile

/**
 * [KeyValueStore] over one Preferences file. Next to each non-permanent record it keeps its retention:
 * `__hb.exp.<key>` (deadline), `__hb.evt.<key>` (event), `__hb.at.<key>` (written at).
 *
 * Before the first operation it deletes expired records and records of events fired while it was closed
 * ([journal]). Logs (`DS`): key names and retention at `D`; values only with [KeyValueSpec.areValuesLogged] (`I`).
 */
internal class LoggingKeyValueStore(
    override val spec: KeyValueSpec,
    private val label: String,
    private val dataStore: DataStore<Preferences>,
    private val clock: RetentionClock,
    private val journal: suspend () -> Map<String, Long>,
    private val scope: ScopeHandle,
) : KeyValueStore {

    private val log = Log.tag(DS_LOG_TAG)
    private val prepareLock = Mutex()

    @Volatile
    private var isPrepared = false

    private val data: Flow<Preferences> = dataStore.data.catch { error ->
        if (error !is IOException) throw error
        log.w(error) { "$label: read failed, using empty data" }
        emit(emptyPreferences())
    }

    override fun <T : Any> observe(key: StoreKey<T>): Flow<T?> = flow {
        withOwnerLifetime {
            log.d { "$label: observe ${key.name}" }
            prepare()
            emitAll(data.map { it.read(key, clock.now()) }.distinctUntilChanged())
        }
    }

    override suspend fun <T : Any> get(key: StoreKey<T>): T? = withOwnerLifetime {
        prepare()
        data.first().read(key, clock.now()).also { value ->
            log.d { "$label: get ${key.name} -> ${if (value == null) "absent" else "present"}" }
        }
    }

    override suspend fun <T : Any> set(key: StoreKey<T>, value: T, retention: Retention): Unit = withOwnerLifetime {
        prepare()
        var old: T? = null
        editWhileOpen("set ${key.name}") { prefs ->
            // inside edit: DataStore serializes edits, so an event purge never sees a stale write time
            val now = clock.now()
            old = prefs.read(key, now)
            prefs.write(key, value)
            prefs.writeRetention(key.name, retention, now)
        }
        if (spec.areValuesLogged) {
            log.i { "$label: set ${key.name}: ${old ?: "absent"} -> $value ($retention)" }
        } else {
            log.d { "$label: set ${key.name} ($retention)" }
        }
    }

    override suspend fun remove(key: StoreKey<*>): Unit = withOwnerLifetime {
        prepare()
        editWhileOpen("remove ${key.name}") { it.removeRecord(key.name) }
        log.d { "$label: remove ${key.name}" }
    }

    override suspend fun clear(): Unit = withOwnerLifetime {
        prepare()
        editWhileOpen("clear") { it.clear() }
        log.i { "$label: cleared" }
    }

    /** Deletes expired records and records of fired events; runs once, before the first operation. */
    suspend fun prepare() {
        if (isPrepared) return
        prepareLock.withLock {
            if (isPrepared) return
            val fired = journal()
            val now = clock.now()
            val removed = purge("open") { prefs, name -> prefs.isExpired(name, now) || prefs.isFiredBy(name, fired) }
            isPrepared = true
            log.d { "$label: opened, purged $removed records" }
        }
    }

    /** Deletes the records whose deadline has passed. */
    suspend fun purgeExpired(): Int {
        val now = clock.now()
        return purge("expiry") { prefs, name -> prefs.isExpired(name, now) }
            .also { removed -> if (removed > 0) log.i { "$label: purged $removed expired records" } }
    }

    /** Deletes the records of [event] written up to [firedAt]. */
    suspend fun purgeEvent(event: String, firedAt: Long): Int =
        purge("event $event") { prefs, name -> prefs.isFiredBy(name, mapOf(event to firedAt)) }
            .also { removed -> log.i { "$label: event $event purged $removed records" } }

    /** Nearest deadline of the records, or `null` when nothing expires. */
    fun nextDeadline(): Flow<Long?> = data.map { prefs ->
        prefs.asMap().entries.filter { it.key.name.startsWith(EXPIRES_PREFIX) }.minOfOrNull { it.value as Long }
    }

    private suspend fun purge(reason: String, matches: (Preferences, String) -> Boolean): Int {
        var removed = 0
        edit("purge ($reason)") { prefs ->
            val names = prefs.asMap().keys.map { it.name }.filterNot { it.startsWith(META_PREFIX) }
            names.filter { matches(prefs, it) }.forEach { name ->
                prefs.removeRecord(name)
                removed++
            }
        }
        return removed
    }

    /** Only this operation is cancelled on close; the shared Preferences file remains app-owned. */
    private suspend fun <T> withOwnerLifetime(block: suspend () -> T): T = coroutineScope {
        checkOpen()
        val operation = coroutineContext.job
        val closeHandle = scope.onClose { operation.cancel(CancellationException("$label: owner closed")) }
        try {
            checkOpen()
            block()
        } finally {
            closeHandle.dispose()
        }
    }

    private fun checkOpen() {
        check(!scope.isClosed) { "$label: storages are closed with scope ${scope.name}" }
    }

    private suspend fun editWhileOpen(operation: String, transform: (MutablePreferences) -> Unit) {
        edit(operation) { prefs ->
            checkOpen()
            transform(prefs)
            checkOpen()
        }
    }

    private suspend fun edit(operation: String, transform: (MutablePreferences) -> Unit) {
        try {
            dataStore.edit { prefs ->
                currentCoroutineContext().ensureActive()
                transform(prefs)
                currentCoroutineContext().ensureActive()
            }
        } catch (e: IOException) {
            log.e(e) { "$label: $operation failed" }
            throw e
        }
    }

    private fun Preferences.isExpired(name: String, now: Long): Boolean =
        this[longPreferencesKey(EXPIRES_PREFIX + name)]?.let { it <= now } == true

    private fun Preferences.isFiredBy(name: String, fired: Map<String, Long>): Boolean {
        val event = this[stringPreferencesKey(EVENT_PREFIX + name)] ?: return false
        val firedAt = fired[event] ?: return false
        val writtenAt = this[longPreferencesKey(WRITTEN_AT_PREFIX + name)] ?: return true
        return writtenAt <= firedAt
    }

    private fun <T : Any> Preferences.read(key: StoreKey<T>, now: Long): T? {
        if (isExpired(key.name, now)) return null
        val raw = this[key.preferencesKey()] ?: return null
        if (!key.type.accepts(raw)) {
            val stored = raw::class.simpleName ?: "a value"
            log.w { "$label: ${key.name} holds $stored, not ${key.type}: treated as absent" }
            return null
        }
        return when (val type = key.type) {
            is StoreValueType.Json -> decode(key, type, raw as String)
            else -> key.type.cast(raw)
        }
    }

    private fun <T : Any> decode(key: StoreKey<T>, type: StoreValueType.Json<T>, raw: String): T? = try {
        json.decodeFromString(type.serializer, raw)
    } catch (e: SerializationException) {
        log.w(e.withoutStoredValue()) { "$label: ${key.name} is not a valid $type, treated as absent" }
        null
    } catch (e: IllegalArgumentException) {
        log.w(e.withoutStoredValue()) { "$label: ${key.name} does not match $type, treated as absent" }
        null
    }

    /** Decoder messages and causes can contain the stored value, including from custom serializers. */
    private fun Throwable.withoutStoredValue(): Throwable =
        SerializationException("Stored value decoding failed (${this::class.simpleName ?: "unknown error"})")

    private fun <T : Any> MutablePreferences.write(key: StoreKey<T>, value: T) {
        val stored: Any = when (val type = key.type) {
            is StoreValueType.Json -> json.encodeToString(type.serializer, value)
            else -> value
        }
        this[key.preferencesKey()] = stored
    }

    private fun MutablePreferences.writeRetention(name: String, retention: Retention, now: Long) {
        putOrRemove(longPreferencesKey(EXPIRES_PREFIX + name), clock.deadline(retention.expiry, now))
        putOrRemove(stringPreferencesKey(EVENT_PREFIX + name), retention.event?.name)
        putOrRemove(longPreferencesKey(WRITTEN_AT_PREFIX + name), now.takeUnless { retention.isPermanent })
    }

    private fun <T> MutablePreferences.putOrRemove(key: Preferences.Key<T>, value: T?) {
        if (value == null) remove(key) else this[key] = value
    }

    /** Preferences keys are equal by name, so the value is removed whatever its type. */
    private fun MutablePreferences.removeRecord(name: String) {
        remove(stringPreferencesKey(name))
        remove(longPreferencesKey(EXPIRES_PREFIX + name))
        remove(stringPreferencesKey(EVENT_PREFIX + name))
        remove(longPreferencesKey(WRITTEN_AT_PREFIX + name))
    }

    private companion object {
        const val META_PREFIX = "__hb."
        const val EXPIRES_PREFIX = "${META_PREFIX}exp."
        const val EVENT_PREFIX = "${META_PREFIX}evt."
        const val WRITTEN_AT_PREFIX = "${META_PREFIX}at."
        val json = Json
    }
}

// The Preferences key type is chosen by StoreValueType<T>, so the stored value is a T (JSON: a String).
@Suppress("UNCHECKED_CAST")
private fun StoreKey<*>.preferencesKey(): Preferences.Key<Any> = when (type) {
    StoreValueType.Text, is StoreValueType.Json -> stringPreferencesKey(name)
    StoreValueType.IntNumber -> intPreferencesKey(name)
    StoreValueType.LongNumber -> longPreferencesKey(name)
    StoreValueType.Flag -> booleanPreferencesKey(name)
    StoreValueType.DoubleNumber -> doublePreferencesKey(name)
    StoreValueType.FloatNumber -> floatPreferencesKey(name)
    StoreValueType.TextSet -> stringSetPreferencesKey(name)
} as Preferences.Key<Any>

/** Whether [raw] was written with this type — a key may have been redeclared with another type. */
private fun StoreValueType<*>.accepts(raw: Any): Boolean = when (this) {
    StoreValueType.Text, is StoreValueType.Json -> raw is String
    StoreValueType.IntNumber -> raw is Int
    StoreValueType.LongNumber -> raw is Long
    StoreValueType.Flag -> raw is Boolean
    StoreValueType.DoubleNumber -> raw is Double
    StoreValueType.FloatNumber -> raw is Float
    StoreValueType.TextSet -> raw is Set<*>
}

// Same reasoning: a value read through preferencesKey() of a StoreValueType<T> (checked by accepts) is a T.
@Suppress("UNCHECKED_CAST")
private fun <T : Any> StoreValueType<T>.cast(raw: Any): T = raw as T
