@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.aequicor.heartbeat.platform.nativelauncher

import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import kotlinx.cinterop.wcstr
import platform.windows.ERROR_SUCCESS
import platform.windows.HKEY
import platform.windows.HKEY_CURRENT_USER
import platform.windows.HKEY__
import platform.windows.KEY_READ
import platform.windows.RegCloseKey
import platform.windows.RegOpenKeyExW
import platform.windows.RegQueryValueExW

/** Тема окна лончера из токенов дизайн-системы (KDoc `HbColors`/`HbSurfaceColors`, пресеты desktop). */
data class LauncherTheme(
    val background: UInt,
    val textPrimary: UInt,
    val textSecondary: UInt,
    val progressTrack: UInt,
    val progressFill: UInt,
    val primaryButton: UInt,
    val primaryButtonText: UInt,
) {
    companion object {
        // HbColors.DesktopLight: background/surface #FFFFFF, textPrimary #242426, primary #E9E5EF.
        private val light = LauncherTheme(
            background = rgb(0xFF, 0xFF, 0xFF),
            textPrimary = rgb(0x24, 0x24, 0x26),
            textSecondary = rgb(0x5A, 0x5A, 0x5C),
            progressTrack = rgb(0xE8, 0xE8, 0xE9),
            progressFill = rgb(0xE9, 0xE5, 0xEF),
            primaryButton = rgb(0xE9, 0xE5, 0xEF),
            primaryButtonText = rgb(0x24, 0x24, 0x26),
        )

        // HbColors.DesktopDark: background #202022, surface #242426, textPrimary #EEEEF0, primary #BFB3D4.
        private val dark = LauncherTheme(
            background = rgb(0x20, 0x20, 0x22),
            textPrimary = rgb(0xEE, 0xEE, 0xF0),
            textSecondary = rgb(0xB3, 0xB3, 0xB6),
            progressTrack = rgb(0x3F, 0x3F, 0x42),
            progressFill = rgb(0xBF, 0xB3, 0xD4),
            primaryButton = rgb(0xBF, 0xB3, 0xD4),
            primaryButtonText = rgb(0x24, 0x24, 0x26),
        )

        private const val BYTE_BITS = 8
        private const val DWORD_BYTES = 4
        private const val THEME_PERSONALIZE_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize"
        private const val APPS_USE_LIGHT_THEME = "AppsUseLightTheme"

        fun current(): LauncherTheme = if (isDarkModePreferred()) dark else light

        private fun isDarkModePreferred(): Boolean {
            memScoped {
                val key = alloc<CPointerVar<HKEY__>>()
                val path = THEME_PERSONALIZE_KEY.wcstr.getPointer(this)
                val opened = RegOpenKeyExW(HKEY_CURRENT_USER, path, 0u, KEY_READ.toUInt(), key.ptr)
                if (opened != ERROR_SUCCESS) return false
                val lightTheme = readDword(key.value, APPS_USE_LIGHT_THEME)
                RegCloseKey(key.value)
                // 0 — приложения в тёмном режиме.
                return lightTheme == 0u
            }
        }

        /** DWORD из реестра; null при отсутствии значения или ошибке чтения. */
        private fun readDword(key: HKEY?, name: String): UInt? = memScoped {
            val value = alloc<UIntVar>()
            val size = alloc<UIntVar>()
            val type = alloc<UIntVar>()
            size.value = DWORD_BYTES.toUInt()
            val result = RegQueryValueExW(
                key,
                name.wcstr.getPointer(this),
                null,
                type.ptr,
                value.ptr.reinterpret<UByteVar>(),
                size.ptr,
            )
            if (result == ERROR_SUCCESS) value.value else null
        }

        private fun rgb(r: Int, g: Int, b: Int): UInt = (r or (g shl BYTE_BITS) or (b shl (2 * BYTE_BITS))).toUInt()
    }
}
