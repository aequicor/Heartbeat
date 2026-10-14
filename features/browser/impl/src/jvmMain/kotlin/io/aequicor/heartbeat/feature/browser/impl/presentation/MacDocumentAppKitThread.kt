package io.aequicor.heartbeat.feature.browser.impl.presentation

import com.sun.jna.Callback
import com.sun.jna.NativeLibrary
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import io.aequicor.heartbeat.core.logging.Log
import java.lang.ref.Reference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

/**
 * A synchronous AppKit hop through common and AWT run-loop modes, matching the desktop capture bridge.
 * Unlike dispatch_sync, it also runs while AppKit waits for EDT inside AWTRunLoopMode. The callback,
 * timer and caller remain alive until work finishes; interruption never abandons native work.
 */
internal class MacDocumentAppKitThread {
    private val log = Log.tag("MacDocumentAppKitThread")
    private val foundation = NativeLibrary.getInstance(
        "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation",
    )
    private val objc = NativeLibrary.getInstance("objc")
    private val system = NativeLibrary.getInstance("/usr/lib/libSystem.B.dylib")
    private val isMain = system.getFunction("pthread_main_np")
    private val poolPush = objc.getFunction("objc_autoreleasePoolPush")
    private val poolPop = objc.getFunction("objc_autoreleasePoolPop")
    private val createString = foundation.getFunction("CFStringCreateWithCString")
    private val now = foundation.getFunction("CFAbsoluteTimeGetCurrent")
    private val createTimer = foundation.getFunction("CFRunLoopTimerCreate")
    private val mainLoop = foundation.getFunction("CFRunLoopGetMain")
    private val addTimer = foundation.getFunction("CFRunLoopAddTimer")
    private val wake = foundation.getFunction("CFRunLoopWakeUp")
    private val invalidate = foundation.getFunction("CFRunLoopTimerInvalidate")
    private val release = foundation.getFunction("CFRelease")
    private val commonModes = foundation.getGlobalVariableAddress("kCFRunLoopCommonModes").getPointer(0)

    fun <T> call(action: () -> T): T {
        if (isMain.invokeInt(emptyArray()) != 0) return action()
        val task = FutureTask {
            val pool = poolPush.invokePointer(emptyArray())
            try {
                check(isMain.invokeInt(emptyArray()) != 0) { "Clipping must run on AppKit" }
                action()
            } finally {
                poolPop.invokeVoid(arrayOf(pool))
            }
        }
        scheduleAndWait(task)
        return try {
            task.get()
        } catch (error: ExecutionException) {
            throw (error.cause ?: error)
        }
    }

    private fun scheduleAndWait(task: FutureTask<*>) {
        val finished = CountDownLatch(1)
        val callback = TimerCallout { _, _ ->
            try {
                task.run()
            } finally {
                finished.countDown()
            }
        }
        val mode = checkNotNull(createString.invokePointer(arrayOf<Any?>(null, "AWTRunLoopMode", UTF8_ENCODING)))
        try {
            val timer = checkNotNull(
                createTimer.invokePointer(
                    arrayOf<Any?>(
                        null,
                        now.invokeDouble(emptyArray()),
                        0.0,
                        NativeLong(0),
                        NativeLong(0),
                        callback,
                        null,
                    ),
                ),
            )
            try {
                val loop = mainLoop.invokePointer(emptyArray())
                addTimer.invokeVoid(arrayOf(loop, timer, commonModes))
                addTimer.invokeVoid(arrayOf(loop, timer, mode))
                wake.invokeVoid(arrayOf(loop))
                awaitFinished(finished)
            } finally {
                invalidate.invokeVoid(arrayOf(timer))
                release.invokeVoid(arrayOf(timer))
                Reference.reachabilityFence(callback)
            }
        } finally {
            release.invokeVoid(arrayOf(mode))
        }
    }

    private fun awaitFinished(finished: CountDownLatch) {
        var isInterrupted = false
        while (finished.count > 0) {
            try {
                finished.await()
            } catch (error: InterruptedException) {
                log.w(error) { "Waiting for native document clipping to finish after interruption" }
                isInterrupted = true
            }
        }
        if (isInterrupted) Thread.currentThread().interrupt()
    }

    private fun interface TimerCallout : Callback {
        fun invoke(timer: Pointer?, info: Pointer?)
    }

    private companion object {
        const val UTF8_ENCODING = 0x08000100
    }
}
