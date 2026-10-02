package io.aequicor.heartbeat.feature.computeruse.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.common.HostPlatform
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseFailure
import io.aequicor.heartbeat.feature.computeruse.api.FramePoint
import io.aequicor.heartbeat.feature.computeruse.api.InputAction
import io.aequicor.heartbeat.feature.computeruse.api.InputOutcome
import io.aequicor.heartbeat.feature.computeruse.api.MouseButton
import io.aequicor.heartbeat.feature.computeruse.impl.domain.InputInjector
import io.aequicor.heartbeat.feature.computeruse.impl.domain.ScreenPoint
import io.aequicor.heartbeat.feature.computeruse.impl.domain.wheelNotches
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.awt.GraphicsEnvironment
import java.awt.Robot
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs

/** Native input calls, separated from action policy so cancellation can be verified without real input. */
internal interface DesktopInputDriver {
    fun move(point: ScreenPoint)
    fun pressButton(mask: Int)
    fun releaseButton(mask: Int)
    fun pressKey(code: Int)
    fun releaseKey(code: Int)
    fun wheel(notches: Int)
    fun pause()
    fun idle()

    /**
     * Types the text exactly as given, independent of the active keyboard layout; newline and tab never appear
     * here. `false` means this driver cannot (try the key-code fallback) or refuses (report the character).
     * An empty text is a capability probe and never injects anything.
     */
    fun typeUnicode(text: String): Boolean = false
}

/** Creates an input device only after the host has authorized an action. */
internal interface DesktopInputDevices {
    val isAvailable: Boolean
    fun create(): DesktopInputDriver
}

/** AWT devices; Robot coordinates are the operating system's logical screen coordinates. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class AwtInputDevices(private val platform: PlatformInfo) : DesktopInputDevices {
    override val isAvailable: Boolean get() = !GraphicsEnvironment.isHeadless()

    override fun create(): DesktopInputDriver = AwtInputDriver(
        Robot().apply { autoDelay = AUTO_DELAY_MILLIS },
        platform.host,
    )

    private companion object {
        const val AUTO_DELAY_MILLIS = 8
    }
}

private class AwtInputDriver(private val robot: Robot, private val host: HostPlatform) : DesktopInputDriver {
    override fun move(point: ScreenPoint) = robot.mouseMove(point.x, point.y)
    override fun pressButton(mask: Int) = robot.mousePress(mask)
    override fun releaseButton(mask: Int) = robot.mouseRelease(mask)
    override fun pressKey(code: Int) = robot.keyPress(injectableKeyCode(code, host))
    override fun releaseKey(code: Int) = robot.keyRelease(injectableKeyCode(code, host))
    override fun wheel(notches: Int) = robot.mouseWheel(notches)
    override fun pause() = robot.delay(STEP_DELAY_MILLIS)
    override fun idle() = robot.waitForIdle()

    override fun typeUnicode(text: String): Boolean =
        if (host == HostPlatform.Windows && WindowsTextBackend.isAvailable) WindowsTextBackend.type(text) else false

    private companion object {
        const val STEP_DELAY_MILLIS = 12
    }
}

/**
 * Translates a logical AWT key code into one this platform's `Robot` accepts. AWT rejects the platform-independent
 * VK_META outright on Windows (`Invalid key code`), where the same key is VK_WINDOWS; other platforms inject the
 * meta key as VK_META. Press and release must translate identically, so a combination never sticks.
 */
internal fun injectableKeyCode(code: Int, host: HostPlatform): Int =
    if (code == KeyEvent.VK_META && host == HostPlatform.Windows) KeyEvent.VK_WINDOWS else code

/**
 * Injects serialized actions on the IO dispatcher. Device creation, progress callbacks and every loop observe
 * cancellation before input continues, and every pressed key or button is released in finally, including
 * failures and the kill switch. Text is validated in its entirety
 * before the first event, so a rejected character cannot leave a partially typed command behind.
 *
 * Text uses US keyboard key positions; unsupported characters are refused instead of silently dropped.
 */
@ContributesBinding(ProfileScope::class)
@Inject
internal class DesktopInputInjector(
    private val dispatchers: DispatcherProvider,
    private val devices: DesktopInputDevices,
) : InputInjector {
    private val log = Log.tag("DesktopInputInjector")
    private val mutex = Mutex()

    override val isAvailable: Boolean get() = devices.isAvailable

    override suspend fun apply(action: InputAction, map: (FramePoint) -> ScreenPoint?): InputOutcome =
        applyObserved(action, map) { }

    override suspend fun applyObserved(
        action: InputAction,
        map: (FramePoint) -> ScreenPoint?,
        onProgress: suspend (ScreenPoint?) -> Unit,
    ): InputOutcome = withContext(dispatchers.io) {
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            val driver = driver() ?: return@withLock InputOutcome.Rejected(ComputerUseFailure.Unavailable)
            currentCoroutineContext().ensureActive()
            var hasStarted = false
            val progress: suspend (ScreenPoint?) -> Unit = { point ->
                if (point != null || !hasStarted) {
                    hasStarted = true
                    onProgress(point)
                }
                currentCoroutineContext().ensureActive()
            }
            try {
                when (action) {
                    is InputAction.MoveTo -> pointer(driver, action.point, map, progress)
                    is InputAction.Click -> click(driver, action, map, progress)
                    is InputAction.Drag -> drag(driver, action, map, progress)
                    is InputAction.Scroll -> scroll(driver, action, map, progress)
                    is InputAction.Type -> type(driver, action.text, progress)
                    is InputAction.Key -> combination(driver, action.keys, progress)
                }.also { currentCoroutineContext().ensureActive() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w(e) { "input injection failed action=${action::class.simpleName.orEmpty()}" }
                InputOutcome.Rejected(ComputerUseFailure.InputRejected)
            }
        }
    }

    private suspend fun pointer(
        driver: DesktopInputDriver,
        point: FramePoint,
        map: (FramePoint) -> ScreenPoint?,
        onProgress: suspend (ScreenPoint?) -> Unit,
    ): InputOutcome {
        val target = map(point) ?: return outside()
        driver.move(target)
        onProgress(target)
        driver.idle()
        return InputOutcome.Applied
    }

    private suspend fun click(
        driver: DesktopInputDriver,
        action: InputAction.Click,
        map: (FramePoint) -> ScreenPoint?,
        onProgress: suspend (ScreenPoint?) -> Unit,
    ): InputOutcome {
        val target = map(action.point) ?: return outside()
        val mask = mask(action.button)
        driver.move(target)
        onProgress(target)
        repeat(action.count.coerceIn(1, MAX_CLICKS)) {
            currentCoroutineContext().ensureActive()
            try {
                driver.pressButton(mask)
            } finally {
                releaseButton(driver, mask)
            }
            driver.pause()
        }
        driver.idle()
        return InputOutcome.Applied
    }

    private suspend fun drag(
        driver: DesktopInputDriver,
        action: InputAction.Drag,
        map: (FramePoint) -> ScreenPoint?,
        onProgress: suspend (ScreenPoint?) -> Unit,
    ): InputOutcome {
        val from = map(action.from) ?: return outside()
        val to = map(action.to) ?: return outside()
        val mask = mask(action.button)
        driver.move(from)
        onProgress(from)
        try {
            driver.pressButton(mask)
            for (step in 1..DRAG_STEPS) {
                currentCoroutineContext().ensureActive()
                val x = from.x + (to.x - from.x) * step / DRAG_STEPS
                val y = from.y + (to.y - from.y) * step / DRAG_STEPS
                driver.move(ScreenPoint(x, y))
                onProgress(ScreenPoint(x, y))
                driver.pause()
            }
        } finally {
            releaseButton(driver, mask)
        }
        driver.idle()
        return InputOutcome.Applied
    }

    private suspend fun scroll(
        driver: DesktopInputDriver,
        action: InputAction.Scroll,
        map: (FramePoint) -> ScreenPoint?,
        onProgress: suspend (ScreenPoint?) -> Unit,
    ): InputOutcome {
        val target = map(action.point) ?: return outside()
        // AWT exposes only the vertical wheel. Refuse horizontal input instead of scrolling the wrong axis.
        if (action.deltaX != 0) return InputOutcome.Rejected(ComputerUseFailure.InputRejected)
        driver.move(target)
        onProgress(target)
        val notches = wheelNotches(action.deltaY)
        // One native event per notch: a single large delta scrolls an unpredictable amount in modern apps.
        val sign = if (notches < 0) -1 else 1
        val count = abs(notches)
        for (notch in 0 until count) {
            currentCoroutineContext().ensureActive()
            driver.wheel(sign)
            if (notch != count - 1) driver.pause()
        }
        driver.idle()
        return InputOutcome.Applied
    }

    private suspend fun type(
        driver: DesktopInputDriver,
        text: String,
        onProgress: suspend (ScreenPoint?) -> Unit,
    ): InputOutcome {
        if (text.length > MAX_TYPED_CHARS) return InputOutcome.Rejected(ComputerUseFailure.InputRejected)
        val segments = textSegments(text)
        // The driver either carries every character itself or none of them: validate the whole text before
        // the first event, so a rejected character cannot leave a partially typed command behind.
        val isUnicodeCapable = driver.typeUnicode("")
        val isMappable = isUnicodeCapable || segments.filterIsInstance<TextSegment.Literal>()
            .all { segment -> segment.text.all { typedKey(it) != null } }
        if (!isMappable) {
            log.w { "typed text refused: unmappable character" }
            return InputOutcome.Rejected(ComputerUseFailure.UnsupportedCharacter)
        }
        typeSegments(driver, segments, isUnicodeCapable, onProgress)
        driver.idle()
        return InputOutcome.Applied
    }

    private suspend fun typeSegments(
        driver: DesktopInputDriver,
        segments: List<TextSegment>,
        isUnicodeCapable: Boolean,
        onProgress: suspend (ScreenPoint?) -> Unit,
    ) {
        for (segment in segments) {
            currentCoroutineContext().ensureActive()
            when (segment) {
                is TextSegment.Newline -> {
                    pressKeys(driver, listOf(KeyEvent.VK_ENTER), onProgress)
                    driver.pause()
                }

                is TextSegment.Tab -> {
                    pressKeys(driver, listOf(KeyEvent.VK_TAB), onProgress)
                    driver.pause()
                }

                is TextSegment.Literal -> if (isUnicodeCapable) {
                    driver.typeUnicode(segment.text)
                    onProgress(null)
                } else {
                    // Validated upfront, so the key-code path always carries the whole run.
                    typeByKeys(driver, segment.text, onProgress)
                }
            }
        }
    }

    /** Types one literal run as layout key codes; the run is validated before the first event. */
    private suspend fun typeByKeys(
        driver: DesktopInputDriver,
        text: String,
        onProgress: suspend (ScreenPoint?) -> Unit,
    ) {
        for (character in text) {
            currentCoroutineContext().ensureActive()
            val key = typedKey(character) ?: continue
            val codes = if (key.isShifted) listOf(KeyEvent.VK_SHIFT, key.code) else listOf(key.code)
            pressKeys(driver, codes, onProgress)
            driver.pause()
        }
    }

    private suspend fun combination(
        driver: DesktopInputDriver,
        keys: List<String>,
        onProgress: suspend (ScreenPoint?) -> Unit,
    ): InputOutcome {
        if (keys.isEmpty()) return InputOutcome.Rejected(ComputerUseFailure.InputRejected)
        val codes = keys.map { namedKey(it) }
        if (codes.any { it == KeyEvent.VK_UNDEFINED }) {
            log.w { "key combination refused: unknown key name count=${keys.size}" }
            return InputOutcome.Rejected(ComputerUseFailure.UnsupportedKey)
        }
        pressKeys(driver, codes, onProgress)
        driver.idle()
        return InputOutcome.Applied
    }

    private suspend fun pressKeys(
        driver: DesktopInputDriver,
        codes: List<Int>,
        onProgress: suspend (ScreenPoint?) -> Unit,
    ) {
        val pressed = mutableListOf<Int>()
        try {
            for (code in codes) {
                currentCoroutineContext().ensureActive()
                // Remember before the native call: it may post the event and then throw.
                pressed += code
                driver.pressKey(code)
                onProgress(null)
            }
        } finally {
            pressed.asReversed().forEach { releaseKey(driver, it) }
        }
    }

    private fun releaseKey(driver: DesktopInputDriver, code: Int) {
        try {
            driver.releaseKey(code)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "pressed key release failed" }
        }
    }

    private fun releaseButton(driver: DesktopInputDriver, mask: Int) {
        try {
            driver.releaseButton(mask)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "pressed button release failed" }
        }
    }

    private fun outside(): InputOutcome {
        log.w { "input refused: the point is outside the captured area" }
        return InputOutcome.Rejected(ComputerUseFailure.RegionOutOfBounds)
    }

    private fun driver(): DesktopInputDriver? = try {
        if (devices.isAvailable) devices.create() else null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e) { "input device unavailable" }
        null
    }

    private fun mask(button: MouseButton): Int = when (button) {
        MouseButton.Left -> InputEvent.BUTTON1_DOWN_MASK
        MouseButton.Right -> InputEvent.BUTTON3_DOWN_MASK
        MouseButton.Middle -> InputEvent.BUTTON2_DOWN_MASK
    }

    private data class TypedKey(val code: Int, val isShifted: Boolean = false)

    private fun typedKey(character: Char): TypedKey? = when (character) {
        in 'a'..'z' -> TypedKey(KeyEvent.VK_A + (character - 'a'))
        in 'A'..'Z' -> TypedKey(KeyEvent.VK_A + (character - 'A'), isShifted = true)
        in '0'..'9' -> TypedKey(KeyEvent.VK_0 + (character - '0'))
        '\n' -> TypedKey(KeyEvent.VK_ENTER)
        '\t' -> TypedKey(KeyEvent.VK_TAB)
        '\b' -> TypedKey(KeyEvent.VK_BACK_SPACE)
        ' ' -> TypedKey(KeyEvent.VK_SPACE)
        else -> punctuationKey(character)
    }

    private fun punctuationKey(character: Char): TypedKey? {
        val plain = PLAIN_SYMBOLS.indexOf(character)
        if (plain >= 0) return TypedKey(SYMBOL_CODES[plain])
        val shifted = SHIFTED_SYMBOLS.indexOf(character)
        if (shifted >= 0) return TypedKey(SYMBOL_CODES[shifted], isShifted = true)
        val digit = SHIFTED_DIGITS.indexOf(character)
        return if (digit >= 0) TypedKey(KeyEvent.VK_0 + (digit + 1) % DIGIT_KEYS, isShifted = true) else null
    }

    private fun namedKey(name: String): Int {
        val key = name.lowercase()
        return firstDefined(modifierKey(key), editingKey(key), navigationKey(key), symbolKey(key), fallbackKey(key))
    }

    private fun firstDefined(vararg codes: Int): Int =
        codes.firstOrNull { it != KeyEvent.VK_UNDEFINED } ?: KeyEvent.VK_UNDEFINED

    private fun modifierKey(name: String): Int = when (name) {
        "ctrl", "control" -> KeyEvent.VK_CONTROL
        "alt", "option" -> KeyEvent.VK_ALT
        "shift" -> KeyEvent.VK_SHIFT
        "meta", "cmd", "command", "win" -> KeyEvent.VK_META
        else -> KeyEvent.VK_UNDEFINED
    }

    private fun editingKey(name: String): Int = when (name) {
        "enter", "return" -> KeyEvent.VK_ENTER
        "tab" -> KeyEvent.VK_TAB
        "esc", "escape" -> KeyEvent.VK_ESCAPE
        "backspace" -> KeyEvent.VK_BACK_SPACE
        "delete" -> KeyEvent.VK_DELETE
        "insert" -> KeyEvent.VK_INSERT
        "space" -> KeyEvent.VK_SPACE
        else -> KeyEvent.VK_UNDEFINED
    }

    private fun navigationKey(name: String): Int = when (name) {
        "home" -> KeyEvent.VK_HOME
        "end" -> KeyEvent.VK_END
        "pageup" -> KeyEvent.VK_PAGE_UP
        "pagedown" -> KeyEvent.VK_PAGE_DOWN
        "up" -> KeyEvent.VK_UP
        "down" -> KeyEvent.VK_DOWN
        "left" -> KeyEvent.VK_LEFT
        "right" -> KeyEvent.VK_RIGHT
        else -> KeyEvent.VK_UNDEFINED
    }

    private fun symbolKey(name: String): Int = when (name) {
        "minus" -> KeyEvent.VK_MINUS
        "equals" -> KeyEvent.VK_EQUALS
        "comma" -> KeyEvent.VK_COMMA
        "period" -> KeyEvent.VK_PERIOD
        "slash" -> KeyEvent.VK_SLASH
        "semicolon" -> KeyEvent.VK_SEMICOLON
        "quote" -> KeyEvent.VK_QUOTE
        "backslash" -> KeyEvent.VK_BACK_SLASH
        "bracketleft" -> KeyEvent.VK_OPEN_BRACKET
        "bracketright" -> KeyEvent.VK_CLOSE_BRACKET
        else -> KeyEvent.VK_UNDEFINED
    }

    private fun fallbackKey(name: String): Int = when {
        name.length == 1 -> typedKey(name[0])?.takeUnless { it.isShifted }?.code ?: KeyEvent.VK_UNDEFINED
        name.startsWith(FUNCTION_KEY_PREFIX) -> functionKey(name.removePrefix(FUNCTION_KEY_PREFIX))
        else -> KeyEvent.VK_UNDEFINED
    }

    private fun functionKey(number: String): Int {
        val index = number.toIntOrNull() ?: return KeyEvent.VK_UNDEFINED
        return when (index) {
            in 1..STANDARD_FUNCTION_KEYS -> KeyEvent.VK_F1 + index - 1
            in FIRST_EXTENDED_FUNCTION_KEY..FUNCTION_KEYS -> KeyEvent.VK_F13 + index - FIRST_EXTENDED_FUNCTION_KEY
            else -> KeyEvent.VK_UNDEFINED
        }
    }

    private companion object {
        const val DRAG_STEPS = 12
        const val MAX_CLICKS = 3
        const val MAX_TYPED_CHARS = 4096
        const val DIGIT_KEYS = 10
        const val FUNCTION_KEY_PREFIX = "f"
        const val STANDARD_FUNCTION_KEYS = 12
        const val FIRST_EXTENDED_FUNCTION_KEY = 13
        const val FUNCTION_KEYS = 24
        const val PLAIN_SYMBOLS = "`-=[]\\;',./"
        const val SHIFTED_SYMBOLS = "~_+{}|:\"<>?"
        const val SHIFTED_DIGITS = "!@#\$%^&*()"
        val SYMBOL_CODES = listOf(
            KeyEvent.VK_BACK_QUOTE, KeyEvent.VK_MINUS, KeyEvent.VK_EQUALS,
            KeyEvent.VK_OPEN_BRACKET, KeyEvent.VK_CLOSE_BRACKET, KeyEvent.VK_BACK_SLASH,
            KeyEvent.VK_SEMICOLON, KeyEvent.VK_QUOTE, KeyEvent.VK_COMMA, KeyEvent.VK_PERIOD, KeyEvent.VK_SLASH,
        )
    }
}
