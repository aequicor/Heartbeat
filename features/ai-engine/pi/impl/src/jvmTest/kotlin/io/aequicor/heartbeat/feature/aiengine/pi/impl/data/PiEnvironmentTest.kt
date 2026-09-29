package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import kotlin.test.Test
import kotlin.test.assertEquals

class PiEnvironmentTest {
    @Test
    fun `windows process keeps shell basics and user locations but no other host state`() {
        val environment = mutableMapOf(
            "Path" to "C:\\Windows\\System32",
            "SystemRoot" to "C:\\Windows",
            "PATHEXT" to ".EXE",
            "TEMP" to "C:\\Users\\dev\\AppData\\Local\\Temp",
            "USERPROFILE" to "C:\\Users\\dev",
            "LOCALAPPDATA" to "C:\\Users\\dev\\AppData\\Local",
            "APPDATA" to "C:\\Users\\dev\\AppData\\Roaming",
            "USERNAME" to "dev",
            "OPENAI_API_KEY" to "vendor key",
            "HTTPS_PROXY" to "http://proxy:3128",
            "JAVA_HOME" to "C:\\jdk",
        )
        retainPiEnvironment(environment)
        assertEquals(
            setOf("Path", "SystemRoot", "PATHEXT", "TEMP", "USERPROFILE", "LOCALAPPDATA", "APPDATA", "USERNAME"),
            environment.keys,
        )
    }

    @Test
    fun `unix process keeps home so host tools resolve the user installation`() {
        val environment = mutableMapOf(
            "PATH" to "/usr/bin",
            "HOME" to "/home/dev",
            "USER" to "dev",
            "TMPDIR" to "/tmp",
            "ANTHROPIC_AUTH_TOKEN" to "vendor token",
        )
        retainPiEnvironment(environment)
        assertEquals(setOf("PATH", "HOME", "USER", "TMPDIR"), environment.keys)
    }

    @Test
    fun `the Python install manager finds the system interpreter through the retained locations`() {
        // A Windows workspace session runs `python` from PATH: the install manager reads LOCALAPPDATA to find
        // the interpreter it manages, and downloads its own into the workspace when that variable is missing.
        val environment = mutableMapOf("LOCALAPPDATA" to "C:\\Users\\dev\\AppData\\Local")
        retainPiEnvironment(environment)
        assertEquals("C:\\Users\\dev\\AppData\\Local", environment["LOCALAPPDATA"])
    }
}
