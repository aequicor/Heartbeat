@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.aequicor.heartbeat.core.secrets.impl.data

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFDictionaryRemoveValue
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanFalse
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecAttrSynchronizable
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

internal class IosProtectedVault : ProtectedVault {
    override fun <T> transaction(profile: String, erase: Boolean, action: (ByteArray?) -> VaultUpdate<T>): T =
        withVaultLock(
            profile,
        ) { locked(profile, erase, action) }

    private fun <T> locked(profile: String, erase: Boolean, action: (ByteArray?) -> VaultUpdate<T>): T = memScoped {
        val query = requireNotNull(CFDictionaryCreateMutable(null, 0, null, null))
        val service = requireNotNull(
            CFStringCreateWithCString(
                null,
                "io.aequicor.heartbeat.secrets.v1".cstr.getPointer(this),
                kCFStringEncodingUTF8,
            ),
        )
        val account = requireNotNull(
            CFStringCreateWithCString(null, profile.cstr.getPointer(this), kCFStringEncodingUTF8),
        )
        try {
            CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionarySetValue(query, kSecAttrService, service)
            CFDictionarySetValue(query, kSecAttrAccount, account)
            CFDictionarySetValue(query, kSecAttrSynchronizable, kCFBooleanFalse)
            val old = if (erase) null else read(query)
            try {
                val update = action(old)
                try {
                    if (update.hasChanges) write(query, update.bytes)
                    update.result
                } finally {
                    update.bytes?.fill(0)
                }
            } finally {
                old?.fill(0)
            }
        } finally {
            CFRelease(account)
            CFRelease(service)
            CFRelease(query)
        }
    }

    private fun read(query: CFDictionaryRef): ByteArray? = memScoped {
        CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
        val result = alloc<CFTypeRefVar>()
        result.value = null
        val status = SecItemCopyMatching(query, result.ptr)
        CFDictionaryRemoveValue(query, kSecReturnData)
        if (status == errSecItemNotFound) return@memScoped null
        check(status == errSecSuccess) { "Keychain read failed ($status)" }
        val data = requireNotNull(result.value)
        try {
            val length = CFDataGetLength(data.reinterpret()).toInt()
            if (length == 0) byteArrayOf() else requireNotNull(CFDataGetBytePtr(data.reinterpret())).readBytes(length)
        } finally {
            CFRelease(data)
        }
    }

    private fun write(query: CFDictionaryRef, bytes: ByteArray?) {
        if (bytes == null) {
            val status = SecItemDelete(query)
            check(status == errSecSuccess || status == errSecItemNotFound) { "Keychain deletion failed ($status)" }
            return
        }
        bytes.usePinned { pinned ->
            val data = requireNotNull(CFDataCreate(null, pinned.addressOf(0).reinterpret(), bytes.size.toLong()))
            val attributes = requireNotNull(CFDictionaryCreateMutable(null, 0, null, null))
            try {
                CFDictionarySetValue(attributes, kSecValueData, data)
                val status = SecItemUpdate(query, attributes)
                if (status == errSecItemNotFound) {
                    CFDictionarySetValue(query, kSecValueData, data)
                    CFDictionarySetValue(query, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
                    check(SecItemAdd(query, null) == errSecSuccess) { "Keychain add failed" }
                } else {
                    check(status == errSecSuccess) { "Keychain update failed ($status)" }
                }
            } finally {
                CFRelease(attributes)
                CFRelease(data)
            }
        }
    }
}
