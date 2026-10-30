@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.aequicor.heartbeat.platform.nativelauncher

import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import kotlinx.cinterop.wcstr
import okio.Path.Companion.toPath
import platform.windows.CLIP_DEFAULT_PRECIS
import platform.windows.CreateFontW
import platform.windows.DEFAULT_CHARSET
import platform.windows.DEFAULT_PITCH
import platform.windows.FF_DONTCARE
import platform.windows.GetFileAttributesW
import platform.windows.HFONT
import platform.windows.HICON
import platform.windows.HICON__
import platform.windows.IMAGE_ICON
import platform.windows.INVALID_FILE_ATTRIBUTES
import platform.windows.LR_LOADFROMFILE
import platform.windows.LoadImageW
import platform.windows.OUT_DEFAULT_PRECIS

/** Matches HbHostFont.jvm and HbTypography.Desktop; every native text surface uses the same font realization. */
internal fun createLauncherFont(height: Int, weight: Int): HFONT = checkNotNull(
    memScoped {
        CreateFontW(
            -height, 0, 0, 0, weight, 0u, 0u, 0u, DEFAULT_CHARSET.toUInt(),
            OUT_DEFAULT_PRECIS.toUInt(), CLIP_DEFAULT_PRECIS.toUInt(), platform.windows.CLEARTYPE_QUALITY.toUInt(),
            (DEFAULT_PITCH or FF_DONTCARE).toUInt(), "Segoe UI".wcstr.getPointer(this),
        )
    },
) { "Cannot create native launcher font" }

internal fun loadLauncherIcon(size: Int): HICON? {
    val candidates = listOf(
        "icons/heartbeat.ico",
        "icons/dev/heartbeat.ico",
        "platform-main/desktop/icons/heartbeat.ico",
        "platform-main/desktop/icons/dev/heartbeat.ico",
    )
    val roots = listOf(executableDirectory(), ".")
    val fromFile = roots.firstNotNullOfOrNull { root ->
        candidates.firstNotNullOfOrNull { candidate ->
            val path = if (root == ".") candidate else "$root/$candidate"
            loadIconFrom(path, size)
        }
    }
    // Файла рядом с exe может не быть (E2E, dev-прогон): тогда иконка — из встроенной копии.
    return fromFile ?: loadEmbeddedIcon(size)
}

private fun loadEmbeddedIcon(size: Int): HICON? {
    val bytes = LauncherConfig.decodeBase64(EMBEDDED_ICON_BASE64)
    val temp = embeddedIconPath(bytes.size) ?: return null
    val fs = okio.FileSystem.SYSTEM
    if (fs.metadataOrNull(temp)?.size != bytes.size.toLong()) {
        fs.write(temp) { write(bytes) }
    }
    return loadIconFrom(temp.toString(), size)
}

private fun embeddedIconPath(size: Int): okio.Path? {
    val temp = launcherEnvironment("TEMP") ?: return null
    return "$temp/heartbeat-launcher-icon-v$ICON_FILE_VERSION-$size.ico".toPath()
}

private fun executableDirectory(): String = checkNotNull(nativeLauncherExecutable().parent).toString()

private fun loadIconFrom(path: String, size: Int): HICON? = memScoped {
    if (GetFileAttributesW(path.wcstr.getPointer(this)) == INVALID_FILE_ATTRIBUTES) return@memScoped null
    LoadImageW(null, path.wcstr.getPointer(this), IMAGE_ICON.toUInt(), size, size, LR_LOADFROMFILE.toUInt())
        ?.reinterpret<HICON__>()
}

private const val ICON_FILE_VERSION = 1

internal fun isLauncherMotionReduced(): Boolean = memScoped {
    val animations = alloc<IntVar>()
    animations.value = 1
    platform.windows.SystemParametersInfoW(GET_CLIENT_AREA_ANIMATION, 0u, animations.ptr, 0u)
    animations.value == 0
}

private const val GET_CLIENT_AREA_ANIMATION = 0x1042u

/** The native process uses system DPI awareness, matching the remote launcher's window sizing contract. */
internal fun launcherSystemDpi(): Int {
    val dc = platform.windows.GetDC(null)
    return try {
        platform.windows.GetDeviceCaps(dc, platform.windows.LOGPIXELSX).coerceAtLeast(DEFAULT_SCREEN_DPI)
    } finally {
        platform.windows.ReleaseDC(null, dc)
    }
}

private const val DEFAULT_SCREEN_DPI = 96
