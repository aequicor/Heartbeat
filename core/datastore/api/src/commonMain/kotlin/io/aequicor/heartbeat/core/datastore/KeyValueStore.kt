package io.aequicor.heartbeat.core.datastore

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.KSerializer

/**
 * Description of a key-value store.
 *
 * @property name file name of the store within its owner: `[a-z][a-z0-9_]*`, unique across all features of the owner —
 *   prefix it with the feature (`chat_settings`); `core_*` is reserved. Stored on disk — never rename.
 * @property defaultRetention retention of [KeyValueStore.set] without an explicit one.
 * @property areValuesLogged log old and new values of changes (`I`) — only for settings without personal data;
 *   otherwise only key names are logged (`D`).
 */
public data class KeyValueSpec(
    public val name: String,
    public val defaultRetention: Retention = Retention.Permanent,
    public val areValuesLogged: Boolean = false,
) {
    init {
        requireStorageName(name)
    }

    override fun toString(): String = "kv $name"
}

/**
 * Key-value store with a per-record [Retention]. Reads never return an expired record, even before the core has
 * deleted it. All functions are main-safe. Changes of every store are logged by the core (tag `DS`).
 */
public interface KeyValueStore {
    /** What this store is. */
    public val spec: KeyValueSpec

    /** Current value of [key] and its changes; `null` when absent or expired. */
    public fun <T : Any> observe(key: StoreKey<T>): Flow<T?>

    /** Current value of [key]; `null` when absent or expired. */
    public suspend fun <T : Any> get(key: StoreKey<T>): T?

    /** Writes [value] with [retention] (the deadline of a time-based expiry is counted from now). */
    public suspend fun <T : Any> set(key: StoreKey<T>, value: T, retention: Retention = spec.defaultRetention)

    /** Removes [key]. */
    public suspend fun remove(key: StoreKey<*>)

    /** Removes every record of the store. */
    public suspend fun clear()
}

/**
 * Typed key of a [KeyValueStore]. Created by [stringKey], [intKey], [longKey], [booleanKey], [doubleKey],
 * [floatKey], [stringSetKey], [jsonKey].
 *
 * @param T type of the value.
 * @property name key name: letters, digits, `_`, `.`, `-`; the `__hb` prefix is reserved. Stored on disk.
 * @property type how the value is stored.
 */
@ConsistentCopyVisibility
public data class StoreKey<T : Any> internal constructor(public val name: String, public val type: StoreValueType<T>) {
    init {
        require(KEY_NAME.matches(name) && !name.startsWith(RESERVED_PREFIX)) {
            "invalid key name '$name': expected ${KEY_NAME.pattern} without the '$RESERVED_PREFIX' prefix"
        }
    }

    override fun toString(): String = name

    private companion object {
        const val RESERVED_PREFIX = "__hb"
        val KEY_NAME = Regex("[A-Za-z0-9_.-]{1,128}")
    }
}

/** How a [StoreKey] value is stored. */
public sealed interface StoreValueType<T : Any> {
    /** [String]. */
    public data object Text : StoreValueType<String>

    /** [Int]. */
    public data object IntNumber : StoreValueType<Int>

    /** [Long]. */
    public data object LongNumber : StoreValueType<Long>

    /** [Boolean]. */
    public data object Flag : StoreValueType<Boolean>

    /** [Double]. */
    public data object DoubleNumber : StoreValueType<Double>

    /** [Float]. */
    public data object FloatNumber : StoreValueType<Float>

    /** `Set<String>`. */
    public data object TextSet : StoreValueType<Set<String>>

    /** Any `@Serializable` value, stored as JSON; an undecodable value reads as absent. */
    @Suppress("UseDataClass") // equal by the serial descriptor: serializer instances have no equality of their own
    public class Json<T : Any>(
        /** Serializer of the value. */
        public val serializer: KSerializer<T>,
    ) : StoreValueType<T> {
        override fun equals(other: Any?): Boolean =
            other is Json<*> && other.serializer.descriptor == serializer.descriptor

        override fun hashCode(): Int = serializer.descriptor.hashCode()

        override fun toString(): String = "Json(${serializer.descriptor.serialName})"
    }
}

/** A [String] key. */
public fun stringKey(name: String): StoreKey<String> = StoreKey(name, StoreValueType.Text)

/** An [Int] key. */
public fun intKey(name: String): StoreKey<Int> = StoreKey(name, StoreValueType.IntNumber)

/** A [Long] key. */
public fun longKey(name: String): StoreKey<Long> = StoreKey(name, StoreValueType.LongNumber)

/** A [Boolean] key. */
public fun booleanKey(name: String): StoreKey<Boolean> = StoreKey(name, StoreValueType.Flag)

/** A [Double] key. */
public fun doubleKey(name: String): StoreKey<Double> = StoreKey(name, StoreValueType.DoubleNumber)

/** A [Float] key. */
public fun floatKey(name: String): StoreKey<Float> = StoreKey(name, StoreValueType.FloatNumber)

/** A `Set<String>` key. */
public fun stringSetKey(name: String): StoreKey<Set<String>> = StoreKey(name, StoreValueType.TextSet)

/** A key of a `@Serializable` value stored as JSON. */
public fun <T : Any> jsonKey(name: String, serializer: KSerializer<T>): StoreKey<T> =
    StoreKey(name, StoreValueType.Json(serializer))
