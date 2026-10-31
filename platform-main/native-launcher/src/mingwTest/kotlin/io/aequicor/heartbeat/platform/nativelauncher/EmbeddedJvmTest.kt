@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.aequicor.heartbeat.platform.nativelauncher

import io.aequicor.heartbeat.platform.nativelauncher.windowsjvm.hb_host_is_composited
import io.aequicor.heartbeat.platform.nativelauncher.windowsjvm.hb_host_set_composited
import io.aequicor.heartbeat.platform.nativelauncher.windowsjvm.hb_jvm_exit_code
import io.aequicor.heartbeat.platform.nativelauncher.windowsjvm.hb_jvm_is_presented
import io.aequicor.heartbeat.platform.nativelauncher.windowsjvm.hb_jvm_start
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.wcstr
import okio.Path.Companion.toPath
import platform.windows.CreateWindowExW
import platform.windows.DestroyWindow
import platform.windows.DispatchMessageW
import platform.windows.GetModuleHandleW
import platform.windows.GetTickCount64
import platform.windows.MSG
import platform.windows.PM_REMOVE
import platform.windows.PeekMessageW
import platform.windows.Sleep
import platform.windows.TranslateMessage
import platform.windows.WS_OVERLAPPEDWINDOW
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EmbeddedJvmTest {
    @Test
    fun arm64PayloadKeepsTheChildProcessWithoutReadingJvmConfiguration() {
        val path = launcherEnvironment("PATH")
        assertEquals(
            false,
            startEmbeddedJvm(
                checkNotNull(1L.toCPointer()),
                "C:/nonexistent-image".toPath(),
                "C:/Heartbeat.exe",
                "windows-arm64",
                emptyList(),
                1.0,
            ),
        )
        assertEquals(path, launcherEnvironment("PATH"))
    }

    @Test
    fun nativeJvmCreatesJawtFrameAndTerminatesAfterDisposal() = memScoped {
        val runtime = assertNotNull(
            launcherEnvironment("HEARTBEAT_JNI_SMOKE_RUNTIME"),
            "Windows JNI smoke requires the packaged JBR",
        )
        val classes = assertNotNull(
            launcherEnvironment("HEARTBEAT_JNI_SMOKE_CLASSES"),
            "Windows JNI smoke requires the compiled fixture classes",
        )
        // A plain window, then the production host style: the smoke proves the style is released, not how it is set.
        val window = assertNotNull(
            CreateWindowExW(
                0u, "STATIC".wcstr.ptr, "Heartbeat JNI test".wcstr.ptr, WS_OVERLAPPEDWINDOW.toUInt(),
                0, 0, 640, 480, null, null, GetModuleHandleW(null), null,
            ),
            "Cannot create the embedding host window",
        )
        try {
            assertEquals(1, hb_host_set_composited(window, 1), "Cannot apply the composited host style")
            installEmbeddedCover(window, 1.0)
            val options = allocArray<CPointerVar<ByteVar>>(2)
            options[0] = "-Djava.class.path=$classes".cstr.ptr
            options[1] = "-Dheartbeat.jni.smoke=ok".cstr.ptr
            val args = allocArray<CPointerVar<UShortVar>>(1)
            args[0] = "Привет 世界".wcstr.ptr
            assertEquals(1, hb_jvm_start(window, runtime.wcstr.ptr, options, 2, args, 1))
            val deadline = GetTickCount64() + TIMEOUT_MILLIS
            val message = alloc<MSG>()
            while (hb_jvm_exit_code() < 0 && GetTickCount64() < deadline) {
                while (PeekMessageW(message.ptr, null, 0u, 0u, PM_REMOVE.toUInt()) != 0) {
                    TranslateMessage(message.ptr)
                    DispatchMessageW(message.ptr)
                }
                // Real OS integration: pump messages while the JVM/AWT threads finish.
                Sleep(10u)
            }
            assertEquals(0, hb_jvm_exit_code(), "JNI/JAWT failed or DestroyJavaVM did not finish")
            assertTrue(hb_jvm_is_presented() != 0)
            // A composited host commits every Studio frame through an off-screen bitmap; the presented
            // Studio window must therefore be a plain window from its first frame on.
            assertEquals(0, hb_host_is_composited(), "The presented Studio kept the launcher composition style")
        } finally {
            DestroyWindow(window)
        }
    }
}

private const val TIMEOUT_MILLIS = 60_000uL
