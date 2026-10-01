// Native exports must retain the symbol names declared by the Windows SDK.
@file:Suppress("FunctionNaming")

package io.aequicor.heartbeat.feature.attachments.impl.data

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.win32.StdCallLibrary
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptInputSupport
import io.aequicor.heartbeat.feature.attachments.api.AttachmentFailure
import io.aequicor.heartbeat.feature.attachments.api.AttachmentInput
import java.awt.KeyboardFocusManager

/**
 * Windows ignores AWT FilenameFilter. OPENFILENAMEW supplies a real native extension mask,
 * with no unrestricted filter, and a Unicode multiple-selection buffer. Imported contents
 * still undergo the shared MIME/signature/size checks before the machine publishes them.
 */
internal fun chooseWindowsAttachments(support: PromptInputSupport): List<AttachmentInput>? {
    val patterns = windowsAttachmentPatterns(support)
    if (patterns.isEmpty()) throw AttachmentValidationException(AttachmentFailure.Unsupported)
    val activeWindow = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
    val owner = if (activeWindow?.isDisplayable == true) Native.getComponentPointer(activeWindow) else null
    val native = Native.load("Comdlg32", WindowsCommonDialogs::class.java)
    val filter = "$patterns\u0000$patterns\u0000\u0000"
    return Memory(filter.length * UTF16_BYTES).use { filterMemory ->
        filter.forEachIndexed { index, character ->
            filterMemory.setShort(
                index * UTF16_BYTES,
                character.code.toShort(),
            )
        }
        Memory(SELECTION_CAPACITY * UTF16_BYTES).use { selection ->
            selection.clear()
            val request = WindowsOpenFileName().apply {
                lStructSize = size()
                hwndOwner = owner
                lpstrFilter = filterMemory
                nFilterIndex = 1
                lpstrFile = selection
                nMaxFile = SELECTION_CAPACITY
                flags = OFN_EXPLORER or OFN_ALLOWMULTISELECT or OFN_FILEMUSTEXIST or OFN_PATHMUSTEXIST or
                    OFN_NOCHANGEDIR or OFN_DONTADDTORECENT
            }
            if (!native.GetOpenFileNameW(request)) {
                val error = native.CommDlgExtendedError()
                check(error == 0) { "Native attachment picker failed ($error)" }
                return@use null
            }
            val characters = CharArray(SELECTION_CAPACITY) { index ->
                (selection.getShort(index * UTF16_BYTES).toInt() and UTF16_MASK).toChar()
            }
            parseWindowsAttachmentSelection(characters.concatToString()).map { path ->
                val name = path.substringAfterLast('\\')
                if (!support.accepts(attachmentMediaType(name))) {
                    throw AttachmentValidationException(AttachmentFailure.Unsupported)
                }
                AttachmentInput.File(path, name)
            }
        }
    }
}

/** Native mask contains exactly the formats confirmed by the model and implemented by this importer. */
internal fun windowsAttachmentPatterns(support: PromptInputSupport): String = support.allowedMediaTypes
    .sorted()
    .flatMap { mime ->
        when (mime) {
            "text/plain" -> listOf("*.txt")
            "text/markdown" -> listOf("*.md", "*.markdown")
            "application/pdf" -> listOf("*.pdf")
            "image/png" -> listOf("*.png")
            "image/jpeg" -> listOf("*.jpg", "*.jpeg")
            "image/gif" -> listOf("*.gif")
            "image/webp" -> listOf("*.webp")
            else -> emptyList()
        }
    }.joinToString(";")

/** Explorer format is either a single full path or a directory followed by NUL-separated basenames. */
internal fun parseWindowsAttachmentSelection(value: String): List<String> {
    val items = value.split('\u0000').takeWhile { it.isNotEmpty() }
    if (items.size < 2) return items
    val directory = items.first().trimEnd('\\')
    return items.drop(1).map { name ->
        require(name !in setOf(".", "..") && '\\' !in name && '/' !in name) { "Invalid native filename" }
        "$directory\\$name"
    }
}

/** Desktop OPENFILENAMEW layout from commdlg.h; LPARAM and callback fields are pointer-sized. */
@Structure.FieldOrder(
    "lStructSize", "hwndOwner", "hInstance", "lpstrFilter", "lpstrCustomFilter", "nMaxCustFilter", "nFilterIndex",
    "lpstrFile", "nMaxFile", "lpstrFileTitle", "nMaxFileTitle", "lpstrInitialDir", "lpstrTitle", "flags", "nFileOffset",
    "nFileExtension", "lpstrDefExt", "lCustData", "lpfnHook", "lpTemplateName", "pvReserved", "dwReserved", "flagsEx",
)
internal class WindowsOpenFileName : Structure() {
    @JvmField var lStructSize: Int = 0

    @JvmField var hwndOwner: Pointer? = null

    @JvmField var hInstance: Pointer? = null

    @JvmField var lpstrFilter: Pointer? = null

    @JvmField var lpstrCustomFilter: Pointer? = null

    @JvmField var nMaxCustFilter: Int = 0

    @JvmField var nFilterIndex: Int = 0

    @JvmField var lpstrFile: Pointer? = null

    @JvmField var nMaxFile: Int = 0

    @JvmField var lpstrFileTitle: Pointer? = null

    @JvmField var nMaxFileTitle: Int = 0

    @JvmField var lpstrInitialDir: Pointer? = null

    @JvmField var lpstrTitle: Pointer? = null

    @JvmField var flags: Int = 0

    @JvmField var nFileOffset: Short = 0

    @JvmField var nFileExtension: Short = 0

    @JvmField var lpstrDefExt: Pointer? = null

    @JvmField var lCustData: Pointer? = null

    @JvmField var lpfnHook: Pointer? = null

    @JvmField var lpTemplateName: Pointer? = null

    @JvmField var pvReserved: Pointer? = null

    @JvmField var dwReserved: Int = 0

    @JvmField var flagsEx: Int = 0
}

private interface WindowsCommonDialogs : StdCallLibrary {
    fun GetOpenFileNameW(request: WindowsOpenFileName): Boolean
    fun CommDlgExtendedError(): Int
}

private const val UTF16_BYTES = 2L
private const val UTF16_MASK = 0xffff
private const val SELECTION_CAPACITY = 32768
private const val OFN_NOCHANGEDIR = 0x00000008
private const val OFN_ALLOWMULTISELECT = 0x00000200
private const val OFN_PATHMUSTEXIST = 0x00000800
private const val OFN_FILEMUSTEXIST = 0x00001000
private const val OFN_EXPLORER = 0x00080000
private const val OFN_DONTADDTORECENT = 0x02000000
