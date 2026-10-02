package io.aequicor.heartbeat.platform.desktop

import com.sun.jna.NativeLibrary
import com.sun.jna.Platform
import com.sun.jna.Pointer
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Runs real AppKit dispatch; macOS with a graphical session only. */
class MacWindowAccessTest {
    private val system by lazy { NativeLibrary.getInstance("/usr/lib/libSystem.B.dylib") }
    private val foundation by lazy {
        NativeLibrary.getInstance("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation")
    }

    @Before
    fun startAppKit() {
        assumeTrue("The AppKit main thread exists only on macOS", Platform.isMac())
        assumeFalse("The AWT event thread needs a graphical session", GraphicsEnvironment.isHeadless())
        // Starts the toolkit, and with it NSApplication, before a test occupies the main thread.
        onEventThread { }
    }

    @Test
    fun `event thread work runs on the AppKit main thread`() {
        val access = MacWindowAccess()
        val isMain = AtomicBoolean(false)
        onEventThread { access.onMainThread { isMain.set(isMainThread()) } }
        assertTrue(isMain.get())
    }

    @Test
    fun `failure of main thread work reaches the event thread caller`() {
        val access = MacWindowAccess()
        val failure = onEventThread {
            assertFailsWith<IllegalStateException> { access.onMainThread { error("window work failed") } }
        }
        assertEquals("window work failed", failure.message)
    }

    @Test
    fun `event thread reaches AppKit while AppKit waits for the event thread`() {
        val access = MacWindowAccess()
        val isAppKitWaiting = CountDownLatch(1)
        val isAnswered = AtomicBoolean(false)
        // Stands in for LWCToolkit.invokeAndWait: AppKit spins only AWTRunLoopMode until the event thread answers.
        val appKit = thread(name = "appkit-waits-for-event-thread") {
            access.onMainThread {
                val mode = foundation.getFunction("CFStringCreateWithCString")
                    .invokePointer(arrayOf<Any?>(null, "AWTRunLoopMode", UTF8_ENCODING))
                try {
                    isAppKitWaiting.countDown()
                    while (!isAnswered.get()) runAwtRunLoopOnce(mode)
                } finally {
                    foundation.getFunction("CFRelease").invokeVoid(arrayOf(mode))
                }
            }
        }
        try {
            assertTrue(isAppKitWaiting.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            val isMain = AtomicBoolean(false)
            onEventThread {
                access.onMainThread { isMain.set(isMainThread()) }
                isAnswered.set(true)
            }
            assertTrue(isMain.get())
        } finally {
            isAnswered.set(true)
            appKit.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
        }
    }

    private fun isMainThread() = system.getFunction("pthread_main_np").invokeInt(emptyArray()) != 0

    private fun runAwtRunLoopOnce(mode: Pointer) {
        foundation.getFunction("CFRunLoopRunInMode").invokeInt(arrayOf<Any?>(mode, RUN_LOOP_SLICE_SECONDS, 0.toByte()))
    }

    /** A deadlock fails the test with a timeout instead of hanging the build. */
    private fun <T> onEventThread(block: () -> T): T {
        val task = FutureTask(block)
        EventQueue.invokeLater(task)
        return try {
            task.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 10L
        const val RUN_LOOP_SLICE_SECONDS = 0.01
        const val UTF8_ENCODING = 0x08000100
    }
}
