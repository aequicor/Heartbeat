package io.aequicor.heartbeat.feature.chromiumbridge.impl.data.hosts

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.Win32Exception
import com.sun.jna.platform.win32.WinReg
import java.io.IOException

/** Default values of per-user registry keys (`HKEY_CURRENT_USER`); failures surface as [IOException]. */
internal interface WindowsRegistry {
    /** The key's default value, or `null` when the key or value is missing. */
    fun defaultValue(key: String): String?

    /** Creates [key] with its parents and sets its default value. */
    fun setDefaultValue(key: String, value: String)

    /** Deletes [key] when it exists. */
    fun deleteKey(key: String)
}

/** [WindowsRegistry] over Advapi32; only touched on Windows. */
internal class JnaWindowsRegistry : WindowsRegistry {
    override fun defaultValue(key: String): String? = registry {
        when {
            !Advapi32Util.registryKeyExists(WinReg.HKEY_CURRENT_USER, key) -> null
            !Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, key, "") -> null
            else -> Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, key, "")
        }
    }

    override fun setDefaultValue(key: String, value: String) = registry {
        Advapi32Util.registryCreateKey(WinReg.HKEY_CURRENT_USER, key)
        Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, key, "", value)
    }

    override fun deleteKey(key: String) = registry {
        if (Advapi32Util.registryKeyExists(WinReg.HKEY_CURRENT_USER, key)) {
            Advapi32Util.registryDeleteKey(WinReg.HKEY_CURRENT_USER, key)
        }
    }

    private inline fun <T> registry(block: () -> T): T = try {
        block()
    } catch (e: Win32Exception) {
        throw IOException("Registry access failed (${e.errorCode})", e)
    }
}
