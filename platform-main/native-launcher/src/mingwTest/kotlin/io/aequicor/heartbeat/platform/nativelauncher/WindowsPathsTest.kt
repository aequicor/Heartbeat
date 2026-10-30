@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.aequicor.heartbeat.platform.nativelauncher

import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.cinterop.wcstr
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.windows.GetFileAttributesW
import platform.windows.GetTempPathW
import platform.windows.INVALID_FILE_ATTRIBUTES
import platform.windows.SetEnvironmentVariableW
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WindowsPathsTest {
    @Test
    fun environmentRetainsUnicodeProfilePaths() = memScoped {
        val name = "HEARTBEAT_UNICODE_PATH_TEST"
        val previous = launcherEnvironment(name)
        val expected = "C:\\Users\\Проверка с пробелами 😀\\AppData\\Roaming"
        try {
            assertTrue(SetEnvironmentVariableW(name.wcstr.ptr, expected.wcstr.ptr) != 0)
            assertEquals(expected, launcherEnvironment(name))
        } finally {
            assertTrue(SetEnvironmentVariableW(name.wcstr.ptr, previous?.wcstr?.getPointer(this)) != 0)
        }
    }

    @Test
    fun fileSystemWritesTheActualUnicodeFilename() = memScoped {
        val capacity = 32768
        val buffer = allocArray<UShortVar>(capacity)
        val length = GetTempPathW(capacity.toUInt(), buffer)
        assertTrue(length > 0u && length < capacity.toUInt())
        val root = buffer.toKString().toPath().resolve("hb-проверка путь-${Random.nextLong()}")
        val fs = FileSystem.SYSTEM
        try {
            fs.createDirectories(root)
            val file = root.resolve("данные.bin")
            fs.write(file) { writeUtf8("Unicode provisioning bytes") }
            assertEquals("Unicode provisioning bytes", fs.read(file) { readUtf8() })
            assertTrue(GetFileAttributesW(file.toString().wcstr.ptr) != INVALID_FILE_ATTRIBUTES)
        } finally {
            fs.deleteRecursively(root, mustExist = false)
        }
    }
}
