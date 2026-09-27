// Native symbols must retain the names exported by Apple's frameworks.
@file:Suppress("FunctionNaming")

package io.aequicor.heartbeat.core.secrets.impl.data

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference

/** Login Keychain SecItem APIs; access follows the system keychain ACL, without secrets in shell arguments. */
internal class MacKeychain(private val serviceName: String) {
    private val cf = Native.load("CoreFoundation", CoreFoundationApi::class.java)
    private val security = Native.load("Security", SecurityApi::class.java)
    private val symbols = NativeLibrary.getInstance("Security")
    private val cfSymbols = NativeLibrary.getInstance("CoreFoundation")

    fun <T> transaction(profile: String, erase: Boolean, action: (ByteArray?) -> VaultUpdate<T>): T {
        val query = dictionary()
        val service = string(serviceName)
        val account = string(profile)
        try {
            set(query, "kSecClass", constant("kSecClassGenericPassword"))
            set(query, "kSecAttrService", service)
            set(query, "kSecAttrAccount", account)
            set(query, "kSecAttrSynchronizable", cfConstant("kCFBooleanFalse"))
            val old = if (erase) null else read(query)
            try {
                val update = action(old)
                try {
                    if (update.hasChanges) write(query, update.bytes)
                    return update.result
                } finally {
                    update.bytes?.fill(0)
                }
            } finally {
                old?.fill(0)
            }
        } finally {
            cf.CFRelease(account)
            cf.CFRelease(service)
            cf.CFRelease(query)
        }
    }

    private fun read(query: Pointer): ByteArray? {
        set(query, "kSecReturnData", cfConstant("kCFBooleanTrue"))
        val result = PointerByReference()
        val status = security.SecItemCopyMatching(query, result)
        cf.CFDictionaryRemoveValue(query, constant("kSecReturnData"))
        if (status == ITEM_NOT_FOUND) return null
        check(status == 0) { "Keychain read failed ($status)" }
        val data = requireNotNull(result.value)
        return try {
            cf.CFDataGetBytePtr(data).getByteArray(0, cf.CFDataGetLength(data).toInt())
        } finally {
            cf.CFRelease(data)
        }
    }

    private fun write(query: Pointer, bytes: ByteArray?) {
        if (bytes == null) {
            val status = security.SecItemDelete(query)
            check(status == 0 || status == ITEM_NOT_FOUND) { "Keychain delete failed ($status)" }
            return
        }
        Memory(bytes.size.coerceAtLeast(1).toLong()).use { memory ->
            memory.write(0, bytes, 0, bytes.size)
            val data = cf.CFDataCreate(null, memory, NativeLong(bytes.size.toLong()))
            val attributes = dictionary()
            try {
                set(attributes, "kSecValueData", data)
                val updated = security.SecItemUpdate(query, attributes)
                if (updated == ITEM_NOT_FOUND) {
                    set(query, "kSecValueData", data)
                    check(security.SecItemAdd(query, null) == 0) { "Keychain add failed" }
                } else {
                    check(updated == 0) { "Keychain update failed ($updated)" }
                }
            } finally {
                cf.CFRelease(attributes)
                cf.CFRelease(data)
                memory.clear()
            }
        }
    }

    private fun dictionary(): Pointer = cf.CFDictionaryCreateMutable(null, NativeLong(0), null, null)
    private fun string(value: String): Pointer = cf.CFStringCreateWithCString(null, value, UTF8)
    private fun constant(name: String): Pointer = symbols.getGlobalVariableAddress(name).getPointer(0)
    private fun cfConstant(name: String): Pointer = cfSymbols.getGlobalVariableAddress(name).getPointer(0)
    private fun set(dictionary: Pointer, name: String, value: Pointer) = cf.CFDictionarySetValue(
        dictionary,
        constant(name),
        value,
    )

    private companion object {
        const val ITEM_NOT_FOUND = -25300
        const val UTF8 = 0x08000100
    }
}

internal interface SecurityApi : Library {
    fun SecItemCopyMatching(query: Pointer, result: PointerByReference): Int
    fun SecItemAdd(query: Pointer, result: PointerByReference?): Int
    fun SecItemUpdate(query: Pointer, attributes: Pointer): Int
    fun SecItemDelete(query: Pointer): Int
}

internal interface CoreFoundationApi : Library {
    fun CFDictionaryCreateMutable(allocator: Pointer?, capacity: NativeLong, keys: Pointer?, values: Pointer?): Pointer
    fun CFDictionarySetValue(dictionary: Pointer, key: Pointer, value: Pointer)
    fun CFDictionaryRemoveValue(dictionary: Pointer, key: Pointer)
    fun CFStringCreateWithCString(allocator: Pointer?, value: String, encoding: Int): Pointer
    fun CFDataCreate(allocator: Pointer?, bytes: Pointer, length: NativeLong): Pointer
    fun CFDataGetLength(data: Pointer): NativeLong
    fun CFDataGetBytePtr(data: Pointer): Pointer
    fun CFRelease(value: Pointer)
}
