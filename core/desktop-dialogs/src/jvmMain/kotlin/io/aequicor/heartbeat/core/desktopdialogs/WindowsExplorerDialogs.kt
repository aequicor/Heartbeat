// Native exports must retain the symbol names declared by the Windows SDK.
@file:Suppress("FunctionNaming")

package io.aequicor.heartbeat.core.desktopdialogs

import com.sun.jna.Function
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.platform.win32.COM.COMUtils
import com.sun.jna.platform.win32.Guid.GUID
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import io.aequicor.heartbeat.core.logging.Log
import java.awt.KeyboardFocusManager

/**
 * The Explorer dialog Windows itself uses, through the COM `IFileDialog` family
 * (`IFileOpenDialog` / `IFileSaveDialog`).
 *
 * AWT's `FileDialog` cannot choose folders on Windows and the OS ignores its filename filter, while Swing's
 * `JFileChooser` is not a system dialog at all — so the interface vtables are called directly through JNA.
 * Slot numbers follow `shobjidl_core.h`: `IUnknown` occupies 0..2, `IModalWindow.Show` is 3, the
 * `IFileDialog` methods run 4..26, `IFileOpenDialog.GetResults` is 27, `IShellItem.GetDisplayName` is 5 and
 * `IShellItemArray.GetCount` / `GetItemAt` are 7 and 8.
 *
 * Captures the owner on the AWT event thread, then runs the entire COM lifetime on a dedicated STA
 * thread. The native modal loop pumps that thread while the AWT event queue remains responsive.
 */
internal class WindowsExplorerDialogs : NativeDialogs {
    private val log = Log.tag("WindowsExplorerDialogs")

    override suspend fun pickDirectory(title: String?): String? = openDialog(
        title = title,
        extensions = emptyList(),
        allowMultiple = false,
        options = FOS_PICKFOLDERS or FOS_FORCEFILESYSTEM or FOS_PATHMUSTEXIST,
    ).firstOrNull()

    override suspend fun pickFiles(title: String?, extensions: List<String>, allowMultiple: Boolean): List<String> =
        openDialog(
            title = title,
            extensions = extensions,
            allowMultiple = allowMultiple,
            options = FOS_FORCEFILESYSTEM or FOS_PATHMUSTEXIST or FOS_FILEMUSTEXIST or FOS_DONTADDTORECENT,
        )

    override suspend fun pickSaveLocation(title: String?, suggestedName: String?, extensions: List<String>): String? =
        withDialogOwner { owner ->
            createDialog(clsidFileSaveDialog, iidFileSaveDialog).use { dialog ->
                dialog.setOptions(
                    FOS_OVERWRITEPROMPT or FOS_FORCEFILESYSTEM or FOS_PATHMUSTEXIST or FOS_DONTADDTORECENT,
                )
                dialog.setTitle(title)
                dialog.setFileTypes(extensions)
                suggestedName?.let { dialog.callChecked(SLOT_SET_FILE_NAME, WString(it)) }
                extensions.firstOrNull()?.let { dialog.callChecked(SLOT_SET_DEFAULT_EXTENSION, WString(it)) }
                when (val shown = dialog.show(owner)) {
                    HRESULT_CANCELLED -> null

                    else -> {
                        check(COMUtils.SUCCEEDED(shown)) { "The Explorer save dialog failed (${shown.code()})" }
                        dialog.singleResult()
                    }
                }
            }
        }

    private suspend fun openDialog(
        title: String?,
        extensions: List<String>,
        allowMultiple: Boolean,
        options: Int,
    ): List<String> = withDialogOwner { owner ->
        createDialog(clsidFileOpenDialog, iidFileOpenDialog).use { dialog ->
            val multiple = if (allowMultiple) FOS_ALLOWMULTISELECT else 0
            dialog.setOptions(options or multiple)
            dialog.setTitle(title)
            dialog.setFileTypes(extensions)
            when (val shown = dialog.show(owner)) {
                HRESULT_CANCELLED -> emptyList()

                else -> {
                    check(COMUtils.SUCCEEDED(shown)) { "The Explorer dialog failed (${shown.code()})" }
                    dialog.selectedPaths()
                }
            }
        }
    }

    private fun createDialog(classId: GUID, interfaceId: GUID): ComObject {
        val instance = PointerByReference()
        val created = ole32.CoCreateInstance(classId, null, CLSCTX_INPROC_SERVER, interfaceId, instance)
        check(COMUtils.SUCCEEDED(created)) { "The Explorer dialog is unavailable (${created.code()})" }
        val pointer = requireNotNull(instance.value) { "The Explorer dialog returned no interface" }
        return ComObject(pointer)
    }

    private suspend fun <T> withDialogOwner(body: (Pointer?) -> T): T {
        val owner = ownerWindow()
        return onDialogThread { inApartment { body(owner) } }
    }

    /** All COM calls, including interface release and apartment teardown, stay on the fresh STA thread. */
    private fun <T> inApartment(body: () -> T): T {
        val initialized = ole32.CoInitializeEx(null, COINIT_APARTMENTTHREADED)
        val owned = COMUtils.SUCCEEDED(initialized)
        check(owned) { "COM is unavailable (${initialized.code()})" }
        return try {
            body()
        } finally {
            if (owned) ole32.CoUninitialize()
        }
    }

    private fun ComObject.setOptions(extra: Int) {
        val current = IntByReference()
        callChecked(SLOT_GET_OPTIONS, current)
        callChecked(SLOT_SET_OPTIONS, current.value or extra)
    }

    private fun ComObject.setTitle(title: String?) {
        if (title != null) callChecked(SLOT_SET_TITLE, WString(title))
    }

    /** One entry of the dialog's type list: Explorer shows the mask itself as the entry label. */
    private fun ComObject.setFileTypes(extensions: List<String>) {
        if (extensions.isEmpty()) return
        val mask = windowsFileMask(extensions)
        val filter = ComdlgFilterSpec().apply {
            name = WString(mask)
            spec = WString(mask)
        }
        filter.write()
        val applied = callResult(SLOT_SET_FILE_TYPES, 1, filter)
        check(COMUtils.SUCCEEDED(applied)) { "The Explorer dialog rejected its type mask (${applied.code()})" }
        callChecked(SLOT_SET_FILE_TYPE_INDEX, 1)
    }

    private fun ComObject.show(owner: Pointer?): Int = callResult(SLOT_SHOW, owner)

    private fun ComObject.singleResult(): String? {
        val item = PointerByReference()
        val result = callResult(SLOT_GET_RESULT, item)
        if (result == HRESULT_CANCELLED) return null
        check(COMUtils.SUCCEEDED(result)) { "The Explorer dialog returned no selection (${result.code()})" }
        return item.value?.let { pointer -> ComObject(pointer).use { shellItem -> shellItem.fileSystemPath() } }
    }

    /** Every selected item, in dialog order; one unreadable item is reported and skipped. */
    private fun ComObject.selectedPaths(): List<String> {
        val items = PointerByReference()
        val result = callResult(SLOT_GET_RESULTS, items)
        check(COMUtils.SUCCEEDED(result)) { "The Explorer dialog returned no selection (${result.code()})" }
        val array = items.value ?: return emptyList()
        return ComObject(array).use { it.paths() }
    }

    private fun ComObject.paths(): List<String> {
        val count = IntByReference()
        val result = callResult(SLOT_ARRAY_GET_COUNT, count)
        check(COMUtils.SUCCEEDED(result)) { "The Explorer selection is unreadable (${result.code()})" }
        return (0 until count.value).mapNotNull { index ->
            val item = PointerByReference()
            val read = callResult(SLOT_ARRAY_GET_ITEM_AT, index, item)
            if (!COMUtils.SUCCEEDED(read)) {
                log.w { "Explorer selection item $index is unreadable (${read.code()})" }
                return@mapNotNull null
            }
            item.value?.let { pointer -> ComObject(pointer).use { shellItem -> shellItem.fileSystemPath() } }
        }
    }

    private fun ownerWindow(): Pointer? = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        .activeWindow
        ?.takeIf { it.isDisplayable }
        ?.let { Native.getComponentPointer(it) }
}

/** COM interface pointer whose methods are called by vtable slot; [close] releases the interface. */
internal class ComObject(private val pointer: Pointer) : AutoCloseable {
    private val log = Log.tag("WindowsExplorerDialogs")
    private val vtable: Pointer = pointer.getPointer(0)

    /** Configuration methods return HRESULT; unlike Release, failure must reach the logging wrapper. */
    fun callChecked(slot: Int, vararg arguments: Any?) {
        val result = callResult(slot, *arguments)
        check(COMUtils.SUCCEEDED(result)) { "Explorer COM method $slot failed (${result.code()})" }
    }

    fun callResult(slot: Int, vararg arguments: Any?): Int = function(slot).invokeInt(arrayOf(pointer, *arguments))

    /** `SIGDN_FILESYSPATH` refuses items without a path; the string is COM task memory and must be freed. */
    fun fileSystemPath(): String? {
        val name = PointerByReference()
        val result = callResult(SLOT_ITEM_GET_DISPLAY_NAME, SIGDN_FILESYSPATH, name)
        val pathPointer = name.value
        if (!COMUtils.SUCCEEDED(result) || pathPointer == null) {
            log.w { "Explorer selection has no file system path (${result.code()})" }
            return null
        }
        return try {
            pathPointer.getWideString(0)
        } finally {
            ole32.CoTaskMemFree(pathPointer)
        }
    }

    // COM is stdcall; on the 64-bit Windows ABI the convention flag makes no difference.
    private fun function(slot: Int): Function =
        Function.getFunction(vtable.getPointer(slot.toLong() * Native.POINTER_SIZE), Function.ALT_CONVENTION)

    override fun close() {
        callResult(SLOT_RELEASE)
    }
}

/** `COMDLG_FILTERSPEC` from `shobjidl_core.h`: the label of a type list entry and its mask. */
@Structure.FieldOrder("name", "spec")
internal class ComdlgFilterSpec : Structure() {
    @JvmField var name: WString? = null

    @JvmField var spec: WString? = null
}

/** `ole32` entry points used by the Explorer dialog; HRESULTs stay plain ints. */
private interface Ole32Library : StdCallLibrary {
    fun CoInitializeEx(reserved: Pointer?, coInit: Int): Int
    fun CoUninitialize()
    fun CoCreateInstance(
        classId: GUID,
        outer: Pointer?,
        classContext: Int,
        interfaceId: GUID,
        instance: PointerByReference,
    ): Int

    fun CoTaskMemFree(pointer: Pointer?)
}

private fun Int.code(): String = "0x" + Integer.toHexString(this)

private val ole32: Ole32Library by lazy {
    Native.load("ole32", Ole32Library::class.java, W32APIOptions.DEFAULT_OPTIONS)
}

private const val HRESULT_CANCELLED = 0x800704C7.toInt() // HRESULT_FROM_WIN32(ERROR_CANCELLED)
private const val COINIT_APARTMENTTHREADED = 0x2
private const val CLSCTX_INPROC_SERVER = 0x1
private const val SIGDN_FILESYSPATH = 0x80058000.toInt()

// FILEOPENDIALOGOPTIONS from shobjidl_core.h.
private const val FOS_OVERWRITEPROMPT = 0x2
private const val FOS_PICKFOLDERS = 0x20
private const val FOS_FORCEFILESYSTEM = 0x40
private const val FOS_ALLOWMULTISELECT = 0x200
private const val FOS_PATHMUSTEXIST = 0x800
private const val FOS_FILEMUSTEXIST = 0x1000
private const val FOS_DONTADDTORECENT = 0x2000000

private const val SLOT_RELEASE = 2
private const val SLOT_SHOW = 3
private const val SLOT_SET_FILE_TYPES = 4
private const val SLOT_SET_FILE_TYPE_INDEX = 5
private const val SLOT_SET_OPTIONS = 9
private const val SLOT_GET_OPTIONS = 10
private const val SLOT_SET_FILE_NAME = 15
private const val SLOT_SET_TITLE = 17
private const val SLOT_GET_RESULT = 20
private const val SLOT_SET_DEFAULT_EXTENSION = 22
private const val SLOT_GET_RESULTS = 27
private const val SLOT_ITEM_GET_DISPLAY_NAME = 5
private const val SLOT_ARRAY_GET_COUNT = 7
private const val SLOT_ARRAY_GET_ITEM_AT = 8

private val clsidFileOpenDialog = GUID("{DC1C5A9C-E88A-4dde-A5A1-60F82A20AEF7}")
private val clsidFileSaveDialog = GUID("{C0B4E2F3-BA21-4773-8DBA-335EC946EB8B}")
private val iidFileOpenDialog = GUID("{d57c7288-d4ad-4768-be02-9d969532d960}")
private val iidFileSaveDialog = GUID("{84bccd23-5fde-4cdb-aea4-af64b83d78ab}")
