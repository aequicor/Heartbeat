package io.aequicor.heartbeat.feature.computeruse.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseFailure
import io.aequicor.heartbeat.feature.computeruse.api.FramePoint
import io.aequicor.heartbeat.feature.computeruse.api.FrameSpace
import io.aequicor.heartbeat.feature.computeruse.api.InputAction
import io.aequicor.heartbeat.feature.computeruse.api.InputOutcome
import io.aequicor.heartbeat.feature.computeruse.api.MouseButton
import io.aequicor.heartbeat.feature.computeruse.impl.domain.InputInjector
import io.aequicor.heartbeat.feature.computeruse.impl.domain.ScreenPoint
import java.awt.GraphicsEnvironment
import java.awt.Robot
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import kotlin.coroutines.cancellation.CancellationException

/**
 * Injects mouse and keyboard events with the AWT robot.
 *
 * The robot posts to the whole desktop, so the coordinator maps every point into physical screen coordinates
 * first and refuses anything outside the captured area: input never reaches a window the caller did not see.
 * Text is typed through key codes; a character without a code is reported as
 * [ComputerUseFailure.UnsupportedCharacter] instead of being dropped, because a silently shortened command is
 * worse than a refused one.
 */
@ContributesBinding(ProfileScope::class)
@Inject
internal class DesktopInputInjector : InputInjector {
    private val log = Log.tag("DesktopInputInjector")

    override val isAvailable: Boolean
        get() = !GraphicsEnvironment.isHeadless()

    override suspend fun apply(action: InputAction, map: (FramePoint) -> ScreenPoint?): InputOutcome {
        val robot = robot() ?: return InputOutcome.Rejected(ComputerUseFailure.Unavailable)
        return try {
            when (action) {
                is InputAction.MoveTo -> pointer(robot, action.point, action.space, map)
                is InputAction.Click -> click(robot, action, map)
                is InputAction.Drag -> drag(robot, action, map)
                is InputAction.Scroll -> scroll(robot, action, map)
                is InputAction.Type -> type(robot, action.text)
                is InputAction.Key -> combination(robot, action.keys)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "input injection failed action=${action::class.simpleName}" }
            InputOutcome.Rejected(ComputerUseFailure.InputRejected)
        }
    }

    private fun pointer(
        robot: Robot,
        point: FramePoint,
        space: FrameSpace?,
        map: (FramePoint) -> ScreenPoint?,
    ): InputOutcome {
        val target = resolve(point, space, map) ?: return outside()
        robot.mouseMove(target.x, target.y)
        robot.waitForIdle()
        return InputOutcome.Applied
    }

    private fun click(robot: Robot, action: InputAction.Click, map: (FramePoint) -> ScreenPoint?): InputOutcome {
        val target = resolve(action.point, action.space, map) ?: return outside()
        val mask = mask(action.button)
        robot.mouseMove(target.x, target.y)
        repeat(action.count.coerceIn(1, MAX_CLICKS)) {
            robot.mousePress(mask)
            robot.mouseRelease(mask)
            robot.delay()
        }
        robot.waitForIdle()
        return InputOutcome.Applied
    }

    private fun drag(robot: Robot, action: InputAction.Drag, map: (FramePoint) -> ScreenPoint?): InputOutcome {
        val from = resolve(action.from, action.space, map) ?: return outside()
        val to = resolve(action.to, action.space, map) ?: return outside()
        val mask = mask(action.button)
        robot.mouseMove(from.x, from.y)
        robot.mousePress(mask)
        for (step in 1..DRAG_STEPS) {
            val x = from.x + (to.x - from.x) * step / DRAG_STEPS
            val y = from.y + (to.y - from.y) * step / DRAG_STEPS
            robot.mouseMove(x, y)
            robot.delay()
        }
        robot.mouseRelease(mask)
        robot.waitForIdle()
        return InputOutcome.Applied
    }

    private fun scroll(robot: Robot, action: InputAction.Scroll, map: (FramePoint) -> ScreenPoint?): InputOutcome {
        val target = resolve(action.point, action.space, map) ?: return outside()
        robot.mouseMove(target.x, target.y)
        val notches = if (action.deltaY != 0) action.deltaY / WHEEL_NOTCH_PX else action.deltaX / WHEEL_NOTCH_PX
        if (notches != 0) robot.mouseWheel(notches)
        robot.waitForIdle()
        return InputOutcome.Applied
    }

    private fun type(robot: Robot, text: String): InputOutcome {
        if (text.isEmpty()) return InputOutcome.Applied
        if (text.length > MAX_TYPED_CHARS) {
            log.w { "typed text refused length=${text.length}" }
            return InputOutcome.Rejected(ComputerUseFailure.InputRejected)
        }
        for (character in text) {
            val code = keyCode(character)
            if (code == KeyEvent.VK_UNDEFINED) {
                log.w { "typed text refused: unmappable character" }
                return InputOutcome.Rejected(ComputerUseFailure.UnsupportedCharacter)
            }
            val needsShift = character.isUpperCase() || character in SHIFTED_SYMBOLS
            if (needsShift) robot.keyPress(KeyEvent.VK_SHIFT)
            robot.keyPress(code)
            robot.keyRelease(code)
            if (needsShift) robot.keyRelease(KeyEvent.VK_SHIFT)
            robot.delay()
        }
        robot.waitForIdle()
        return InputOutcome.Applied
    }

    private fun combination(robot: Robot, keys: List<String>): InputOutcome {
        if (keys.isEmpty()) return InputOutcome.Rejected(ComputerUseFailure.InputRejected)
        val codes = keys.map { namedKey(it) }
        if (codes.any { it == KeyEvent.VK_UNDEFINED }) {
            log.w { "key combination refused: unknown key name count=${keys.size}" }
            return InputOutcome.Rejected(ComputerUseFailure.InputRejected)
        }
        codes.forEach { robot.keyPress(it) }
        codes.asReversed().forEach { robot.keyRelease(it) }
        robot.waitForIdle()
        return InputOutcome.Applied
    }

    private fun resolve(point: FramePoint, space: FrameSpace?, map: (FramePoint) -> ScreenPoint?): ScreenPoint? {
        if (space == null) return null
        return map(point)
    }

    private fun outside(): InputOutcome {
        log.w { "input refused: the point is outside the captured area" }
        return InputOutcome.Rejected(ComputerUseFailure.RegionOutOfBounds)
    }

    private fun robot(): Robot? = try {
        if (GraphicsEnvironment.isHeadless()) null else Robot().apply { autoDelay = AUTO_DELAY_MILLIS }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e) { "input device unavailable" }
        null
    }

    private fun Robot.delay() {
        delay(STEP_DELAY_MILLIS)
    }

    private fun mask(button: MouseButton): Int = when (button) {
        MouseButton.Left -> InputEvent.BUTTON1_DOWN_MASK
        MouseButton.Right -> InputEvent.BUTTON3_DOWN_MASK
        MouseButton.Middle -> InputEvent.BUTTON2_DOWN_MASK
    }

    private fun keyCode(character: Char): Int = when (character) {
        '\n' -> KeyEvent.VK_ENTER
        '\t' -> KeyEvent.VK_TAB
        '\b' -> KeyEvent.VK_BACK_SPACE
        else -> KeyEvent.getExtendedKeyCodeForChar(character.code)
    }

    private fun namedKey(name: String): Int {
        val key = name.lowercase()
        return firstDefined(modifierKey(key), editingKey(key), navigationKey(key), symbolKey(key), fallbackKey(key))
    }

    /** The first code that is not `VK_UNDEFINED`. */
    private fun firstDefined(vararg codes: Int): Int =
        codes.firstOrNull { it != KeyEvent.VK_UNDEFINED } ?: KeyEvent.VK_UNDEFINED

    /** Modifier keys of a combination. */
    private fun modifierKey(name: String): Int = when (name) {
        "ctrl", "control" -> KeyEvent.VK_CONTROL
        "alt", "option" -> KeyEvent.VK_ALT
        "shift" -> KeyEvent.VK_SHIFT
        "meta", "cmd", "command", "win" -> KeyEvent.VK_META
        else -> KeyEvent.VK_UNDEFINED
    }

    /** Keys that edit text or activate the focused control. */
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

    /** Cursor and paging keys. */
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

    /** Named punctuation keys. */
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

    /** Single characters and `f1..f24`; anything else is refused. */
    private fun fallbackKey(name: String): Int = when {
        name.length == 1 -> keyCode(name[0])
        name.startsWith(FUNCTION_KEY_PREFIX) -> functionKey(name.removePrefix(FUNCTION_KEY_PREFIX))
        else -> KeyEvent.VK_UNDEFINED
    }

    private fun functionKey(number: String): Int {
        val index = number.toIntOrNull() ?: return KeyEvent.VK_UNDEFINED
        if (index !in 1..FUNCTION_KEYS) return KeyEvent.VK_UNDEFINED
        return KeyEvent.VK_F1 + (index - 1)
    }

    private companion object {
        const val AUTO_DELAY_MILLIS = 8
        const val STEP_DELAY_MILLIS = 12
        const val DRAG_STEPS = 12
        const val MAX_CLICKS = 3
        const val MAX_TYPED_CHARS = 4096
        const val WHEEL_NOTCH_PX = 40
        const val FUNCTION_KEYS = 24
        const val FUNCTION_KEY_PREFIX = "f"
        const val SHIFTED_SYMBOLS = "~!@#\$%^&*()_+{}|:\"<>?"
    }
}
