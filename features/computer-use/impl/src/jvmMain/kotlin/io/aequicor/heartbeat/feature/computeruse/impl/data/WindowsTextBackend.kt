package io.aequicor.heartbeat.feature.computeruse.impl.data

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.Union
import com.sun.jna.win32.StdCallLibrary
import io.aequicor.heartbeat.core.logging.Log
import kotlin.coroutines.cancellation.CancellationException

/**
 * Types exact characters on Windows through `SendInput` with `KEYEVENTF_UNICODE`. Synthetic key codes go through
 * the active keyboard layout, so on a non-English layout Latin text arrives as another script; Unicode events
 * carry the character itself and bypass the layout. Every failure closes: a missing library makes the backend
 * unavailable, and a refused call reports `false` for the caller to report.
 */
internal object WindowsTextBackend {
    private val log = Log.tag("WindowsTextBackend")
    private val user32: SendInputLib? = load()

    /** `true` when the native library was loaded. */
    val isAvailable: Boolean get() = user32 != null

    /**
     * Injects every UTF-16 code unit as one press-release pair in a single `SendInput` call, so surrogate pairs
     * arrive as two characters. `false` when the system inserted fewer events than requested; some of them may
     * already have arrived. UIPI blocking of an elevated window either refuses the call without naming the cause or
     * reports the text as typed although it never arrives.
     */
    fun type(text: String): Boolean {
        val library = user32 ?: return false
        if (text.isEmpty()) return true
        val events = unicodeEvents(text)
        val sent = try {
            library.SendInput(events.size, events, events.first().size())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "unicode typing failed chars=${text.length}" }
            return false
        }
        if (sent != events.size) {
            log.w { "unicode typing refused sent=$sent of=${events.size} error=${Native.getLastError()}" }
            return false
        }
        return true
    }

    private fun load(): SendInputLib? = try {
        Native.load("user32", SendInputLib::class.java)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e) { "native library user32 is unavailable" }
        null
    } catch (e: LinkageError) {
        // UnsatisfiedLinkError on a failed load, NoClassDefFoundError when JNA itself failed to initialize earlier.
        log.w(e) { "native library user32 cannot be linked" }
        null
    }
}

/**
 * Keyboard events that type [text] as Unicode characters: a press and a release per UTF-16 code unit. `SendInput`
 * reads a C array, so the events share one contiguous native block; JNA refuses an array of separately allocated
 * structures before the call.
 */
internal fun unicodeEvents(text: String): Array<SendInputEvent> {
    if (text.isEmpty()) return emptyArray()
    val events = SendInputEvent().toArray(text.length * 2) as Array<SendInputEvent>
    events.forEachIndexed { index, event ->
        // Elements after the first are read back from zeroed memory, so every field is set explicitly.
        event.type = INPUT_KEYBOARD
        event.input.setType(KeybdInput::class.java)
        event.input.keyboard.wScan = text[index / 2].code.toShort()
        event.input.keyboard.dwFlags = if (index % 2 == 0) UNICODE_ONLY else UNICODE_ONLY or KEY_UP
    }
    return events
}

private const val INPUT_KEYBOARD = 1
private const val KEY_UP = 0x0002
private const val UNICODE_ONLY = 0x0004

/** `user32` entry point for synthesized input used by the Unicode typing backend. */
@Suppress("FunctionNaming") // Win32 symbol names are fixed by the native ABI
internal interface SendInputLib : StdCallLibrary {
    fun SendInput(count: Int, events: Array<SendInputEvent>, size: Int): Int
}

/**
 * Win32 `INPUT`: the payload tag followed by the member union. The union mirrors every member so the native
 * size matches the platform `sizeof(INPUT)`, which `SendInput` validates; only keyboard events are ever sent.
 */
@Structure.FieldOrder("type", "input")
internal class SendInputEvent : Structure() {
    @JvmField var type: Int = INPUT_KEYBOARD

    @JvmField var input: InputUnion = InputUnion()
}

/** Win32 `INPUT` payload union; JNA sizes it by the largest member and aligns it like the native declaration. */
internal class InputUnion : Union() {
    @JvmField var keyboard: KeybdInput = KeybdInput()

    @JvmField var mouse: MouseInput = MouseInput()
}

/** Win32 `KEYBDINPUT`; `wVk` stays zero because the scan code carries the Unicode character. */
@Structure.FieldOrder("wVk", "wScan", "dwFlags", "time", "dwExtraInfo")
internal class KeybdInput : Structure() {
    @JvmField var wVk: Short = 0

    @JvmField var wScan: Short = 0

    @JvmField var dwFlags: Int = 0

    @JvmField var time: Int = 0

    @JvmField var dwExtraInfo: Pointer? = null
}

/** Win32 `MOUSEINPUT`, declared only so the union keeps the native `INPUT` size. */
@Structure.FieldOrder("dx", "dy", "mouseData", "dwFlags", "time", "dwExtraInfo")
internal class MouseInput : Structure() {
    @JvmField var dx: Int = 0

    @JvmField var dy: Int = 0

    @JvmField var mouseData: Int = 0

    @JvmField var dwFlags: Int = 0

    @JvmField var time: Int = 0

    @JvmField var dwExtraInfo: Pointer? = null
}
