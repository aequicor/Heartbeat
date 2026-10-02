package io.aequicor.heartbeat.platform.desktop

import com.sun.jna.Callback
import com.sun.jna.Function
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.NativeLong
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinUser
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUsePresentation
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseSuppressionReason
import java.awt.Dialog
import java.awt.EventQueue
import java.awt.Frame
import java.awt.Window
import java.lang.ref.Reference
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import javax.swing.JFrame

/**
 * Hides the session and its owned native windows during pixel capture or pointer input.
 * Independent indicators are suppressed only for pixel capture and stay visible during input.
 * Java window visibility stays unchanged, preserving Compose composition, window state and capture
 * registration. Restoration neither activates a window nor resurrects a disposed presentation.
 */
internal class DesktopCapturePresentation(private val window: JFrame, private val overlay: ComputerUseScreenOverlay) :
    ComputerUsePresentation,
    AutoCloseable {
    private val log = Log.tag("DesktopCapturePresentation")
    private var isClosed = false
    private var suppressionCount = 0
    private var shadowPause: AutoCloseable? = null
    private var nativeSuppression: NativeCaptureSuppression? = null
    private val suppressor by lazy {
        when {
            Platform.isMac() -> MacCaptureSuppressor()
            Platform.isWindows() -> WindowsCaptureSuppressor()
            else -> error("Capture presentation requires macOS or Windows")
        }
    }

    override fun suppress(): AutoCloseable = suppress(ComputerUseSuppressionReason.CapturePixels)

    override fun suppress(reason: ComputerUseSuppressionReason): AutoCloseable {
        checkEventThread()
        check(!isClosed) { "The capture presentation was disposed" }
        if (suppressionCount == 0) {
            val pause = if (reason == ComputerUseSuppressionReason.CapturePixels) overlay.pauseForCapture() else null
            var isSuppressed = false
            try {
                nativeSuppression = suppressor.suppress(window)
                shadowPause = pause
                isSuppressed = true
            } finally {
                if (!isSuppressed) pause?.close()
            }
            log.d { "Session presentation suppressed reason=$reason" }
        }
        suppressionCount++
        var isReleased = false
        return AutoCloseable {
            checkEventThread()
            if (!isReleased) {
                isReleased = true
                suppressionCount--
                if (suppressionCount == 0) restorePresentation()
            }
        }
    }

    override fun close() {
        checkEventThread()
        isClosed = true
        overlay.close()
        // Active leases retain native handles until capture's finally block releases them.
    }

    private fun restorePresentation() {
        val pause = shadowPause
        val suppression = nativeSuppression
        shadowPause = null
        nativeSuppression = null
        try {
            // Apply pending shadow changes first: disposed peers must never be restored by the native lease.
            pause?.close()
        } finally {
            suppression?.restore(!isClosed)
        }
        log.d { "Computer-use pixel capture finished; presentation restored" }
    }

    private fun checkEventThread() {
        check(EventQueue.isDispatchThread()) { "Capture presentation must run on the AWT event thread" }
    }
}

private fun interface NativeCaptureSuppression {
    fun restore(isPresentationAlive: Boolean)
}

private fun interface CaptureSuppressor {
    fun suppress(window: JFrame): NativeCaptureSuppression
}

private fun ownedWindowTree(window: Window): List<Window> = buildList {
    add(window)
    window.ownedWindows.forEach { child -> addAll(ownedWindowTree(child)) }
}

/** One failed peer must not prevent the remaining app windows from being restored. */
private fun <T> restoreEvery(items: List<T>, restore: (T) -> Unit) {
    val log = Log.tag("DesktopCapturePresentation")
    var firstFailure: Throwable? = null
    items.forEach { item ->
        // FutureTask records even cancellation/native linkage failures so later peers still get cleanup.
        val task = FutureTask { restore(item) }
        task.run()
        try {
            task.get()
        } catch (e: ExecutionException) {
            val failure = e.cause ?: e
            log.w(e) { "A captured presentation window could not be restored" }
            val previous = firstFailure
            when {
                previous == null -> firstFailure = failure

                failure is CancellationException && previous !is CancellationException -> {
                    failure.addSuppressed(previous)
                    firstFailure = failure
                }

                else -> previous.addSuppressed(failure)
            }
        }
    }
    firstFailure?.let { throw it }
}

/** Native ShowWindow bypasses AWT visibility callbacks; DwmFlush finishes composition before capture. */
private class WindowsCaptureSuppressor : CaptureSuppressor {
    private val flush = NativeLibrary.getInstance("dwmapi").getFunction("DwmFlush", Function.ALT_CONVENTION)

    override fun suppress(window: JFrame): NativeCaptureSuppression {
        val snapshots = ownedWindowTree(window).filter(Window::isDisplayable).mapNotNull { owned ->
            val handle = HWND(Native.getWindowPointer(owned))
            if (User32.INSTANCE.IsWindowVisible(handle)) WindowsHiddenWindow(owned, handle) else null
        }
        val lease = NativeCaptureSuppression { isAlive ->
            restoreEvery(snapshots) { saved ->
                if (isAlive && isRestorable(saved)) {
                    User32.INSTANCE.ShowWindow(saved.handle, WinUser.SW_SHOWNA)
                    check(User32.INSTANCE.IsWindowVisible(saved.handle)) { "The session window could not be restored" }
                }
            }
        }
        var isSuppressed = false
        try {
            snapshots.asReversed().forEach { saved ->
                User32.INSTANCE.ShowWindow(saved.handle, WinUser.SW_HIDE)
                check(!User32.INSTANCE.IsWindowVisible(saved.handle)) { "The session window is still visible" }
            }
            check(flush.invokeInt(emptyArray()) == 0) { "Desktop composition did not finish hiding the session" }
            isSuppressed = true
        } finally {
            if (!isSuppressed) lease.restore(true)
        }
        return lease
    }

    private fun isRestorable(saved: WindowsHiddenWindow): Boolean {
        if (!saved.window.isDisplayable || !saved.window.isVisible) return false
        if (!User32.INSTANCE.IsWindow(saved.handle)) return false
        return Native.getWindowPointer(saved.window) == saved.handle.pointer
    }
}

private data class WindowsHiddenWindow(val window: Window, val handle: HWND)

/** Retains NSWindows until restoration and checks WindowServer metadata before screenshots can proceed. */
private class MacCaptureSuppressor : CaptureSuppressor {
    private val cocoa = MacWindowAccess()
    private val graphics = NativeLibrary.getInstance("/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics")
    private val foundation = NativeLibrary.getInstance(
        "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation",
    )

    override fun suppress(window: JFrame): NativeCaptureSuppression {
        val snapshots = snapshotWindows(window)
        val lease = NativeCaptureSuppression { isAlive ->
            cocoa.onMainThread {
                val isOwnerVisible = isAlive && window.isDisplayable && window.isVisible
                restoreEvery(snapshots) { saved ->
                    try {
                        if (isOwnerVisible && isRestorable(saved)) {
                            cocoa.send(saved.native, "orderFront:", null)
                        }
                    } finally {
                        try {
                            cocoa.send(saved.native, "setAnimationBehavior:", NativeLong(saved.animation))
                        } finally {
                            cocoa.send(saved.native, "release")
                        }
                    }
                }
            }
        }
        var isSuppressed = false
        try {
            cocoa.onMainThread {
                snapshots.asReversed().forEach { saved ->
                    cocoa.send(saved.native, "setAnimationBehavior:", NativeLong(NO_ANIMATION))
                    cocoa.send(saved.native, "orderOut:", null)
                    check(!cocoa.boolean(saved.native, "isVisible")) { "The session window is still visible" }
                }
                cocoa.flushTransactions()
                verifyWindowServer(snapshots)
            }
            isSuppressed = true
        } finally {
            if (!isSuppressed) lease.restore(true)
        }
        return lease
    }

    private fun snapshotWindows(window: JFrame): List<MacHiddenWindow> {
        var snapshots = emptyList<MacHiddenWindow>()
        cocoa.onMainThread {
            if (window.isDisplayable) {
                val javaWindows = ownedWindowTree(window)
                // AWT owners are not necessarily NSWindow.childWindows (notably POPUP shadow panels).
                val nativeRoots = javaWindows.filter { it.isDisplayable && it.isVisible }.map { owner ->
                    val title = checkNotNull(owner.nativeTitle()) {
                        "An owned app window has no safe native capture identity"
                    }
                    cocoa.window(title)
                }
                snapshots = nativeRoots.asSequence().flatMap(::nativeTree)
                    .distinct()
                    .filter { cocoa.boolean(it, "isVisible") }
                    .map { native -> snapshotWindow(native, javaWindows) }
                    .toList()
                snapshots.forEach { cocoa.send(it.native, "retain") }
            }
        }
        return snapshots
    }

    private fun snapshotWindow(native: Pointer, owners: List<Window>): MacHiddenWindow {
        val title = cocoa.title(native)
        val owner = checkNotNull(owners.singleOrNull { it.nativeTitle() == title }) {
            "Cannot safely map an owned native window to its Java lifetime"
        }
        return MacHiddenWindow(
            native,
            cocoa.number(native, "windowNumber"),
            owner,
            title,
            cocoa.number(native, "animationBehavior"),
        )
    }

    private fun isRestorable(saved: MacHiddenWindow): Boolean {
        if (!saved.owner.isDisplayable || !saved.owner.isVisible) return false
        if (cocoa.number(saved.native, "windowNumber") != saved.number) return false
        return cocoa.windows(saved.title).singleOrNull() == saved.native
    }

    private fun nativeTree(window: Pointer): List<Pointer> = buildList {
        add(window)
        cocoa.optionalPointer(window, "childWindows")?.let { children ->
            cocoa.objects(children).forEach { child -> addAll(nativeTree(child)) }
        }
    }

    private fun verifyWindowServer(snapshots: List<MacHiddenWindow>) {
        if (snapshots.isEmpty()) return
        val list = checkNotNull(
            graphics.getFunction("CGWindowListCopyWindowInfo").invokePointer(arrayOf<Any>(ON_SCREEN_ONLY, 0)),
        ) {
            "WindowServer could not confirm that the session was hidden"
        }
        try {
            val numberKey = graphics.getGlobalVariableAddress("kCGWindowNumber").getPointer(0)
            val visible = cocoa.objects(list).map { entry ->
                cocoa.number(cocoa.pointer(entry, "objectForKey:", numberKey), "longLongValue")
            }.toSet()
            check(snapshots.none { it.number in visible }) { "The session is still present in desktop composition" }
        } finally {
            foundation.getFunction("CFRelease").invokeVoid(arrayOf(list))
        }
    }

    private fun Window.nativeTitle(): String? = when (this) {
        is Frame -> title
        is Dialog -> title
        else -> null
    }

    private companion object {
        const val ON_SCREEN_ONLY = 1
        const val NO_ANIMATION = 2L
    }
}

private data class MacHiddenWindow(
    val native: Pointer,
    val number: Long,
    val owner: Window,
    val title: String,
    val animation: Long,
)

/**
 * Shared Cocoa access avoids private JAWT pointer layouts; every operation runs on the AppKit main thread.
 *
 * Work reaches the main thread through a one-shot `CFRunLoopTimer` registered for the common run loop modes and
 * for AWT's own `AWTRunLoopMode`, never through `dispatch_sync` on the main queue. While AppKit synchronously waits
 * for the AWT event thread (`LWCToolkit.invokeAndWait` from accessibility clients such as VoiceOver, input methods,
 * live resize) it spins a nested run loop only in `AWTRunLoopMode`, where the main queue is not serviced: a
 * blocking main-queue dispatch from the event thread would then wait for AppKit while AppKit waits for the event
 * thread, freezing the whole application. The JDK reaches AppKit from the event thread the same way
 * (`performSelectorOnMainThread:` with run loop modes that include `AWTRunLoopMode`).
 */
internal class MacWindowAccess {
    private val log = Log.tag("MacWindowAccess")
    private val cocoa = NativeLibrary.getInstance("/System/Library/Frameworks/AppKit.framework/AppKit")
    private val objc = NativeLibrary.getInstance("objc")
    private val system = NativeLibrary.getInstance("/usr/lib/libSystem.B.dylib")
    private val quartz = NativeLibrary.getInstance("/System/Library/Frameworks/QuartzCore.framework/QuartzCore")
    private val foundation = NativeLibrary.getInstance(
        "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation",
    )
    private val message = objc.getFunction("objc_msgSend")
    private val selector = objc.getFunction("sel_registerName")
    private val isMainThread = system.getFunction("pthread_main_np")

    // Resolved up front: once a timer is scheduled, nothing may fail before its callout is known to be finished.
    private val poolPush = objc.getFunction("objc_autoreleasePoolPush")
    private val poolPop = objc.getFunction("objc_autoreleasePoolPop")
    private val createString = foundation.getFunction("CFStringCreateWithCString")
    private val currentTime = foundation.getFunction("CFAbsoluteTimeGetCurrent")
    private val createTimer = foundation.getFunction("CFRunLoopTimerCreate")
    private val mainRunLoop = foundation.getFunction("CFRunLoopGetMain")
    private val addTimer = foundation.getFunction("CFRunLoopAddTimer")
    private val wakeUp = foundation.getFunction("CFRunLoopWakeUp")
    private val invalidateTimer = foundation.getFunction("CFRunLoopTimerInvalidate")
    private val release = foundation.getFunction("CFRelease")
    private val commonModes = foundation.getGlobalVariableAddress("kCFRunLoopCommonModes").getPointer(0)

    // JNA frees a callback's native trampoline together with its Java object, so a single callout lives as long
    // as this access object and finds each timer's work by the timer it fires for.
    private val scheduled = ConcurrentHashMap<Pointer, Runnable>()
    private val callout = RunLoopTimerCallout { timer, _ -> timer?.let(scheduled::remove)?.run() }

    fun window(title: String): Pointer {
        val found = windows(title)
        check(found.size == 1) { "Cannot uniquely locate the native session window" }
        return found.single()
    }

    fun windows(title: String): List<Pointer> {
        val app = checkNotNull(cocoa.getGlobalVariableAddress("NSApp").getPointer(0)) {
            "The AWT application has no native NSApplication"
        }
        return objects(pointer(app, "windows")).filter { title(it) == title }
    }

    fun title(window: Pointer): String = pointer(pointer(window, "title"), "UTF8String").getString(0, "UTF-8")

    fun objects(array: Pointer): List<Pointer> = (0 until number(array, "count")).map { index ->
        pointer(array, "objectAtIndex:", NativeLong(index))
    }

    fun pointer(receiver: Pointer, method: String, vararg args: Any?): Pointer =
        checkNotNull(optionalPointer(receiver, method, *args)) { "Cocoa returned nil from $method" }

    fun optionalPointer(receiver: Pointer, method: String, vararg args: Any?): Pointer? =
        message.invokePointer(arrayOf(receiver, select(method), *args))

    fun number(receiver: Pointer, method: String): Long = message.invokeLong(arrayOf(receiver, select(method)))

    fun boolean(receiver: Pointer, method: String): Boolean =
        message.invokeInt(arrayOf(receiver, select(method))) and BOOLEAN_MASK != 0

    fun send(receiver: Pointer, method: String, vararg args: Any?) {
        message.invokeVoid(arrayOf(receiver, select(method), *args))
    }

    fun flushTransactions() {
        send(quartz.getGlobalVariableAddress("OBJC_CLASS_\$_CATransaction"), "flush")
    }

    fun onMainThread(action: () -> Unit) {
        if (isMainThread.invokeInt(emptyArray()) != 0) {
            action()
        } else {
            // FutureTask carries every failure, including cancellation and the pool calls', across the JNA callback.
            val task = FutureTask {
                // The run loop drains autorelease pools only in AppKit's modes, not in AWTRunLoopMode.
                val pool = poolPush.invokePointer(emptyArray())
                try {
                    action()
                } finally {
                    poolPop.invokeVoid(arrayOf(pool))
                }
            }
            runOnMainRunLoop(task)
            try {
                task.get()
            } catch (e: ExecutionException) {
                throw (e.cause ?: e)
            }
        }
    }

    /** Runs [task] from a one-shot main run loop timer and returns only after the timer's work has finished. */
    private fun runOnMainRunLoop(task: FutureTask<Unit>) {
        val finished = CountDownLatch(1)
        val work = Runnable {
            try {
                task.run()
            } finally {
                finished.countDown()
            }
        }
        val awtMode = checkNotNull(createString.invokePointer(arrayOf<Any?>(null, AWT_RUN_LOOP_MODE, UTF8_ENCODING))) {
            "Cannot name the AWT run loop mode"
        }
        try {
            val now = currentTime.invokeDouble(emptyArray())
            val timer = checkNotNull(
                createTimer.invokePointer(arrayOf<Any?>(null, now, 0.0, NativeLong(0), NativeLong(0), callout, null)),
            ) {
                "Cannot schedule Cocoa work on the main run loop"
            }
            try {
                scheduled[timer] = work
                val main = mainRunLoop.invokePointer(emptyArray())
                addTimer.invokeVoid(arrayOf(main, timer, commonModes))
                addTimer.invokeVoid(arrayOf(main, timer, awtMode))
                wakeUp.invokeVoid(arrayOf(main))
                awaitUninterruptibly(finished)
            } finally {
                scheduled.remove(timer)
                invalidateTimer.invokeVoid(arrayOf(timer))
                release.invokeVoid(arrayOf(timer))
            }
        } finally {
            release.invokeVoid(arrayOf(awtMode))
            // Pins the shared callout (and its native trampoline) until the timer can no longer fire.
            Reference.reachabilityFence(this)
        }
    }

    /** Like dispatch_sync, waiting is not interruptible: the scheduled work may already be running. */
    private fun awaitUninterruptibly(finished: CountDownLatch) {
        var interruption: InterruptedException? = null
        while (finished.count > 0) {
            try {
                finished.await()
            } catch (e: InterruptedException) {
                log.w(e) { "Interrupted while AppKit runs window work; waiting for it to finish" }
                interruption = e
            }
        }
        if (interruption != null) Thread.currentThread().interrupt()
    }

    private fun select(name: String): Pointer = selector.invokePointer(arrayOf(name))

    /** `CFRunLoopTimerCallBack`: `void (*)(CFRunLoopTimerRef timer, void *info)`. */
    private fun interface RunLoopTimerCallout : Callback {
        fun invoke(timer: Pointer?, info: Pointer?)
    }

    private companion object {
        const val BOOLEAN_MASK = 0xff

        // ThreadUtilities' javaRunLoopMode: AppKit spins it while waiting for the AWT event thread.
        const val AWT_RUN_LOOP_MODE = "AWTRunLoopMode"
        const val UTF8_ENCODING = 0x08000100
    }
}
