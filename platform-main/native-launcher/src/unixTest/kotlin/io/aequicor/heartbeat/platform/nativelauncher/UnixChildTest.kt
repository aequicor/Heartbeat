package io.aequicor.heartbeat.platform.nativelauncher

import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UnixChildTest {
    @Test
    fun argumentAndEnvironmentBoundariesSurviveUnicodeAndSpaces() {
        val file = FileSystem.SYSTEM_TEMPORARY_DIRECTORY.resolve("hb-child-${Random.nextLong()}")
        try {
            val child = UnixChild.start(
                "/bin/sh".toPath(),
                listOf(
                    "-c",
                    "printf '%s\\n%s' \"$1\" \"${'$'}HEARTBEAT_TEST_VALUE\" > \"$2\"; exit 23",
                    "heartbeat-test",
                    "Аргумент с пробелами и 'quotes'",
                    file.toString(),
                ),
                mapOf("HEARTBEAT_TEST_VALUE" to "environment value with spaces"),
            )
            assertEquals(23, child.waitForExit())
            assertEquals(23, child.poll())
            assertEquals(
                "Аргумент с пробелами и 'quotes'\nenvironment value with spaces",
                FileSystem.SYSTEM.read(file) { readUtf8() },
            )
            assertTrue(nativeLauncherExecutable().isAbsolute)
        } finally {
            FileSystem.SYSTEM.delete(file, mustExist = false)
        }
    }
}
