@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.aequicor.heartbeat.platform.nativelauncher

import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.cinterop.wcstr
import platform.windows.CloseHandle
import platform.windows.CommandLineToArgvW
import platform.windows.CreatePipe
import platform.windows.ExitProcess
import platform.windows.GetEnvironmentVariableW
import platform.windows.GetStdHandle
import platform.windows.HANDLE
import platform.windows.LocalFree
import platform.windows.ReadFile
import platform.windows.STD_OUTPUT_HANDLE
import platform.windows.SetEnvironmentVariableW
import platform.windows.SetStdHandle
import platform.windows.WriteFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WindowsProcessTest {
    @Test
    fun operatingSystemParserPreservesUnicodeQuotesEmptyAndTrailingSlashes() = memScoped {
        val expected = listOf("", "space path", "ключ", "a\"b", "C:\\space path\\", "a\\\"b")
        val count = alloc<IntVar>()
        val parsed = assertNotNull(
            CommandLineToArgvW(windowsCommandLine("C:\\Heartbeat.exe", expected).wcstr.ptr, count.ptr),
        )
        try {
            assertEquals(expected.size + 1, count.value)
            assertEquals(expected, (1 until count.value).map { assertNotNull(parsed[it]).toKString() })
        } finally {
            LocalFree(parsed)
        }
    }

    @Test
    fun applicationExitCodeDoesNotSignExtendItsLowByte() {
        val child = launchProbe("exit")
        try {
            assertEquals(200, waitForProcessExit(child))
        } finally {
            CloseHandle(child)
        }
    }

    @Test
    fun childReceivesANonInheritableParentStdoutPipeThroughAnExplicitDuplicate() = memScoped {
        val read = alloc<COpaquePointerVar>()
        val write = alloc<COpaquePointerVar>()
        assertTrue(CreatePipe(read.ptr, write.ptr, null, 0u) != 0)
        val original = GetStdHandle(STD_OUTPUT_HANDLE)
        try {
            assertTrue(SetStdHandle(STD_OUTPUT_HANDLE, write.value) != 0)
            val child = try {
                launchProbe("stdio")
            } finally {
                assertTrue(SetStdHandle(STD_OUTPUT_HANDLE, original) != 0)
            }
            try {
                assertEquals(0, waitForProcessExit(child))
            } finally {
                CloseHandle(child)
            }
            CloseHandle(write.value)
            write.value = null
            val bytes = ByteArray(64)
            val count = alloc<UIntVar>()
            bytes.usePinned {
                assertTrue(ReadFile(read.value, it.addressOf(0), bytes.size.toUInt(), count.ptr, null) != 0)
            }
            assertEquals("stdio-probe", bytes.decodeToString(0, count.value.toInt()))
        } finally {
            SetStdHandle(STD_OUTPUT_HANDLE, original)
            write.value?.let { CloseHandle(it) }
            CloseHandle(read.value)
        }
    }

    /** The child is this Kotlin/Native executable, with the same argv/stdio contract as the actual launcher. */
    @Test
    fun childProcessProbe() {
        when (probeMode()) {
            null -> return

            "exit" -> ExitProcess(200u)

            "stdio" -> memScoped {
                val bytes = "stdio-probe".encodeToByteArray()
                val written = alloc<UIntVar>()
                val result = bytes.usePinned {
                    WriteFile(GetStdHandle(STD_OUTPUT_HANDLE), it.addressOf(0), bytes.size.toUInt(), written.ptr, null)
                }
                ExitProcess(if (result != 0 && written.value == bytes.size.toUInt()) 0u else 3u)
            }

            else -> ExitProcess(4u)
        }
    }

    private fun probeMode(): String? = memScoped {
        val buffer = allocArray<UShortVar>(128)
        val size = GetEnvironmentVariableW(PROBE_MODE.wcstr.ptr, buffer, 128u)
        check(size < 128u) { "Unexpected process probe mode length" }
        if (size == 0u) null else buffer.toKString()
    }

    private fun launchProbe(mode: String): HANDLE = memScoped {
        val previous = probeMode()
        assertTrue(SetEnvironmentVariableW(PROBE_MODE.wcstr.ptr, mode.wcstr.ptr) != 0)
        try {
            assertNotNull(
                launchProcess(
                    nativeLauncherExecutable().toString(),
                    listOf("--ktest_logger=SILENT", "--ktest_filter=*WindowsProcessTest.childProcessProbe*"),
                ),
            )
        } finally {
            assertTrue(SetEnvironmentVariableW(PROBE_MODE.wcstr.ptr, previous?.wcstr?.getPointer(this)) != 0)
        }
    }

    companion object {
        private const val PROBE_MODE = "HEARTBEAT_NATIVE_PROCESS_TEST_MODE"
    }
}
