@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.aequicor.heartbeat.platform.nativelauncher

import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.cinterop.wcstr
import platform.posix.getenv
import platform.windows.CloseHandle
import platform.windows.CommandLineToArgvW
import platform.windows.CreatePipe
import platform.windows.GetStdHandle
import platform.windows.LocalFree
import platform.windows.ReadFile
import platform.windows.STD_OUTPUT_HANDLE
import platform.windows.SetStdHandle
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
        val systemRoot = assertNotNull(getenv("SystemRoot")).toKString()
        val child = assertNotNull(launchProcess("$systemRoot/System32/cmd.exe", listOf("/d", "/c", "exit 200")))
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
        val systemRoot = assertNotNull(getenv("SystemRoot")).toKString()
        try {
            assertTrue(SetStdHandle(STD_OUTPUT_HANDLE, write.value) != 0)
            val child = try {
                assertNotNull(launchProcess("$systemRoot/System32/cmd.exe", listOf("/d", "/c", "echo stdio-probe")))
            } finally {
                assertTrue(SetStdHandle(STD_OUTPUT_HANDLE, original) != 0)
            }
            try {
                assertEquals(0, waitForProcessExit(child))
            } finally {
                CloseHandle(child)
            }
            val bytes = ByteArray(64)
            val count = alloc<UIntVar>()
            bytes.usePinned {
                assertTrue(
                    ReadFile(read.value, it.addressOf(0), bytes.size.toUInt(), count.ptr, null) != 0,
                )
            }
            assertEquals("stdio-probe", bytes.decodeToString(0, count.value.toInt()).trim())
        } finally {
            SetStdHandle(STD_OUTPUT_HANDLE, original)
            CloseHandle(write.value)
            CloseHandle(read.value)
        }
    }
}
