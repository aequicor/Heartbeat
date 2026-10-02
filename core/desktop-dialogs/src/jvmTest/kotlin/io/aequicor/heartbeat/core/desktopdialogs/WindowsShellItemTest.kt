package io.aequicor.heartbeat.core.desktopdialogs

import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.COM.COMUtils
import com.sun.jna.platform.win32.Guid.GUID
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Explorer selection is unwrapped through `IShellItem::GetDisplayName`; this test builds a shell item
 * from a known path and reads it back with the same vtable call the dialog result uses, without any UI.
 */
class WindowsShellItemTest {
    @Test
    fun `shell items report their file system path through the vtable`() {
        if (!Platform.isWindows()) return
        val directory = Files.createTempDirectory("heartbeat-dialogs")
        try {
            inApartment {
                createShellItem(directory.toString()).use { item ->
                    assertEquals(directory.toString(), item.fileSystemPath())
                }
            }
        } finally {
            Files.deleteIfExists(directory)
        }
    }

    private fun inApartment(body: () -> Unit) {
        val initialized = ole32Test.CoInitializeEx(null, COINIT_APARTMENTTHREADED)
        try {
            body()
        } finally {
            if (COMUtils.SUCCEEDED(initialized)) ole32Test.CoUninitialize()
        }
    }

    private fun createShellItem(path: String): ComObject {
        val instance = PointerByReference()
        val created = shell32.SHCreateItemFromParsingName(WString(path), null, iidShellItem, instance)
        assertTrue(COMUtils.SUCCEEDED(created), "shell item creation failed with $created")
        return ComObject(requireNotNull(instance.value) { "shell item creation returned no interface" })
    }

    private interface Shell32Library : StdCallLibrary {
        fun SHCreateItemFromParsingName(path: WString, bindContext: Pointer?, iid: GUID, item: PointerByReference): Int
    }

    private interface Ole32TestLibrary : StdCallLibrary {
        fun CoInitializeEx(reserved: Pointer?, coInit: Int): Int

        fun CoUninitialize()
    }

    private companion object {
        private const val COINIT_APARTMENTTHREADED = 0x2

        val shell32: Shell32Library by lazy {
            Native.load("shell32", Shell32Library::class.java, W32APIOptions.DEFAULT_OPTIONS)
        }
        val ole32Test: Ole32TestLibrary by lazy {
            Native.load("ole32", Ole32TestLibrary::class.java, W32APIOptions.DEFAULT_OPTIONS)
        }
        val iidShellItem = GUID("{43826d1e-e718-42ee-bc55-a1e261c37bfe}")
    }
}
