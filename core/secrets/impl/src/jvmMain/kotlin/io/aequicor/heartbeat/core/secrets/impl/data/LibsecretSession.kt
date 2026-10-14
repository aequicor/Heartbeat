// Native symbols must retain the names exported by libsecret and GLib.
@file:Suppress("FunctionNaming")

package io.aequicor.heartbeat.core.secrets.impl.data

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.ptr.NativeLongByReference
import com.sun.jna.ptr.PointerByReference

/** Uses the stable libsecret-1 ABI already provided by desktop Linux; no shell command carries a secret. */
internal fun openLibsecretSession(serviceName: String, profile: String): LinuxKeyringSession =
    LibsecretConnection().open(serviceName, profile)

private class LibsecretConnection {
    private val secret = Native.load("libsecret-1.so.0", LibsecretApi::class.java)
    private val glib = Native.load("libglib-2.0.so.0", GlibApi::class.java)
    private val objects = Native.load("libgobject-2.0.so.0", GObjectApi::class.java)
    private val glibSymbols = NativeLibrary.getInstance("libglib-2.0.so.0")

    fun open(serviceName: String, profile: String): LinuxKeyringSession {
        val resources = NativeResources()
        var transferred = false
        try {
            // Fresh proxies observe the current keyring lock state without a UI/main-loop dependency.
            val service = resources.own(
                required(
                    checked { error ->
                        secret.secret_service_open_sync(
                            secret.secret_service_get_type(),
                            null,
                            OPEN_SESSION,
                            null,
                            error,
                        )
                    },
                ),
                objects::g_object_unref,
            )
            val collection = resources.own(
                required(
                    checked { error ->
                        secret.secret_collection_for_alias_sync(service, "default", 0, null, error)
                    },
                ),
                objects::g_object_unref,
            )
            checkUnlocked(collection, null)
            val attributes = attributes(resources, serviceName, profile)
            val matches = checked { error ->
                // Never request SECRET_SEARCH_UNLOCK: a locked entry must not look like a missing credential.
                secret.secret_collection_search_sync(collection, null, attributes, SEARCH_ALL, null, error)
            }
            val items = mutableListOf<Pointer>()
            if (matches != null) {
                resources.own(matches, glib::g_list_free)
                var cursor: Pointer? = matches
                while (cursor != null) {
                    items += resources.own(required(cursor.getPointer(0)), objects::g_object_unref)
                    cursor = cursor.getPointer(Native.POINTER_SIZE.toLong())
                }
            }
            check(items.size <= 1) { "Linux keyring contains duplicate profile entries" }
            val item = items.singleOrNull()
            checkUnlocked(collection, item)
            val session = Session(collection, attributes, item, resources)
            transferred = true
            return session
        } finally {
            if (!transferred) resources.close()
        }
    }

    private fun attributes(resources: NativeResources, serviceName: String, profile: String): Pointer {
        val attributes = resources.own(
            required(
                glib.g_hash_table_new_full(
                    glibSymbols.getFunction("g_str_hash"),
                    glibSymbols.getFunction("g_str_equal"),
                    glibSymbols.getFunction("g_free"),
                    glibSymbols.getFunction("g_free"),
                ),
            ),
            glib::g_hash_table_unref,
        )
        glib.g_hash_table_insert(attributes, required(glib.g_strdup("service")), required(glib.g_strdup(serviceName)))
        glib.g_hash_table_insert(attributes, required(glib.g_strdup("account")), required(glib.g_strdup(profile)))
        return attributes
    }

    private inner class Session(
        private val collection: Pointer,
        private val attributes: Pointer,
        private val item: Pointer?,
        private val resources: NativeResources,
    ) : LinuxKeyringSession {
        override fun read(): ByteArray? {
            checkUnlocked(collection, item)
            val existing = item ?: return null
            check(checked { secret.secret_item_load_secret_sync(existing, null, it) } != 0) {
                "Linux keyring could not load the profile secret"
            }
            val value = required(secret.secret_item_get_secret(existing))
            return try {
                val length = NativeLongByReference()
                val bytes = required(secret.secret_value_get(value, length))
                val size = length.value.toLong()
                check(size in 0..Int.MAX_VALUE.toLong()) { "Linux keyring returned an invalid secret length" }
                bytes.getByteArray(0, size.toInt())
            } finally {
                secret.secret_value_unref(value)
            }
        }

        override fun write(bytes: ByteArray?) {
            checkUnlocked(collection, item)
            if (bytes == null) {
                if (item != null) {
                    check(checked { secret.secret_item_delete_sync(item, null, it) } != 0) {
                        "Linux keyring could not delete the profile secret"
                    }
                }
                return
            }
            Memory(bytes.size.coerceAtLeast(1).toLong()).use { memory ->
                try {
                    memory.write(0, bytes, 0, bytes.size)
                    val value = required(
                        secret.secret_value_new(memory, NativeLong(bytes.size.toLong()), "application/octet-stream"),
                    )
                    try {
                        storeValue(value)
                    } finally {
                        secret.secret_value_unref(value)
                    }
                } finally {
                    memory.clear()
                }
            }
        }

        private fun storeValue(value: Pointer) {
            if (item == null) {
                val created = required(
                    checked { error ->
                        secret.secret_item_create_sync(
                            collection,
                            null,
                            attributes,
                            "Heartbeat profile secrets",
                            value,
                            REPLACE_ITEM,
                            null,
                            error,
                        )
                    },
                )
                objects.g_object_unref(created)
            } else {
                check(checked { secret.secret_item_set_secret_sync(item, value, null, it) } != 0) {
                    "Linux keyring could not update the profile secret"
                }
            }
        }

        override fun close() = resources.close()
    }

    private fun checkUnlocked(collection: Pointer, item: Pointer?) {
        if (secret.secret_collection_get_locked(collection) != 0 ||
            (item != null && secret.secret_item_get_locked(item) != 0)
        ) {
            throw ProtectedVaultAccessException(VaultAccessProblem.LinuxKeyringLocked)
        }
    }

    private fun required(pointer: Pointer?): Pointer =
        pointer ?: throw ProtectedVaultAccessException(VaultAccessProblem.LinuxKeyringUnavailable)

    private fun <T> checked(call: (PointerByReference) -> T): T {
        val error = PointerByReference()
        val result = call(error)
        error.value?.let { nativeError ->
            // GError.message may contain input values. Free it without reading or logging its contents.
            glib.g_error_free(nativeError)
            throw ProtectedVaultAccessException(VaultAccessProblem.LinuxKeyringUnavailable)
        }
        return result
    }

    private companion object {
        const val OPEN_SESSION = 2
        const val SEARCH_ALL = 2
        const val REPLACE_ITEM = 2
    }
}

private class NativeResources : AutoCloseable {
    private val releases = mutableListOf<() -> Unit>()

    fun own(pointer: Pointer, release: (Pointer) -> Unit): Pointer {
        releases += { release(pointer) }
        return pointer
    }

    override fun close() {
        releases.asReversed().forEach { it() }
        releases.clear()
    }
}

@Suppress("LongParameterList", "TooManyFunctions") // Signatures exactly match libsecret's stable C ABI.
internal interface LibsecretApi : Library {
    fun secret_service_get_type(): NativeLong
    fun secret_service_open_sync(
        type: NativeLong,
        busName: String?,
        flags: Int,
        cancel: Pointer?,
        error: PointerByReference,
    ): Pointer?
    fun secret_collection_for_alias_sync(
        service: Pointer,
        alias: String,
        flags: Int,
        cancel: Pointer?,
        error: PointerByReference,
    ): Pointer?
    fun secret_collection_get_locked(collection: Pointer): Int
    fun secret_collection_search_sync(
        collection: Pointer,
        schema: Pointer?,
        attributes: Pointer,
        flags: Int,
        cancel: Pointer?,
        error: PointerByReference,
    ): Pointer?
    fun secret_item_get_locked(item: Pointer): Int
    fun secret_item_load_secret_sync(item: Pointer, cancel: Pointer?, error: PointerByReference): Int
    fun secret_item_get_secret(item: Pointer): Pointer?
    fun secret_item_set_secret_sync(item: Pointer, value: Pointer, cancel: Pointer?, error: PointerByReference): Int
    fun secret_item_create_sync(
        collection: Pointer,
        schema: Pointer?,
        attributes: Pointer,
        label: String,
        value: Pointer,
        flags: Int,
        cancel: Pointer?,
        error: PointerByReference,
    ): Pointer?
    fun secret_item_delete_sync(item: Pointer, cancel: Pointer?, error: PointerByReference): Int
    fun secret_value_new(bytes: Pointer, length: NativeLong, contentType: String): Pointer?
    fun secret_value_get(value: Pointer, length: NativeLongByReference): Pointer?
    fun secret_value_unref(value: Pointer)
}

internal interface GlibApi : Library {
    fun g_hash_table_new_full(hash: Pointer, equals: Pointer, keyDestroy: Pointer, valueDestroy: Pointer): Pointer?
    fun g_hash_table_insert(table: Pointer, key: Pointer, value: Pointer): Int
    fun g_hash_table_unref(table: Pointer)
    fun g_strdup(value: String): Pointer?
    fun g_list_free(list: Pointer)
    fun g_error_free(error: Pointer)
}

internal interface GObjectApi : Library {
    fun g_object_unref(value: Pointer)
}
