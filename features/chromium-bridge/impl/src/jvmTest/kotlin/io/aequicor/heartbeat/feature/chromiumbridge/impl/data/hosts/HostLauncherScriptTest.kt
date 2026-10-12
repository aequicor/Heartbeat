package io.aequicor.heartbeat.feature.chromiumbridge.impl.data.hosts

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HostLauncherScriptTest {
    private val socket = Path.of("/Users/o'brien/Library/Heartbeat/run/bridge.sock")

    @Test
    fun `packaged macOS wrapper runs the launcher in host mode with quoted paths`() {
        val launcher = HostLauncher.Packaged(Path.of("/Applications/Heartbeat \$1.app/Contents/MacOS/io.aequicor"))

        val script = HostLauncherScript.script(launcher, socket, isWindows = false)

        assertEquals(
            "#!/bin/sh\n" +
                "# Written by Heartbeat: starts the native-messaging host of the Chromium bridge.\n" +
                "JAVA_TOOL_OPTIONS='-Xmx96m -XX:+UseSerialGC -XX:-UsePerfData -XX:+DisplayVMOutputToStderr " +
                "-Djava.awt.headless=true'\nexport JAVA_TOOL_OPTIONS\n" +
                "exec '/Applications/Heartbeat \$1.app/Contents/MacOS/io.aequicor' '--heartbeat-chromium-host' " +
                "'/Users/o'\\''brien/Library/Heartbeat/run/bridge.sock' \"\$@\"\n",
            script,
        )
        assertEquals("heartbeat-chromium-host", HostLauncherScript.fileName(launcher, isWindows = false))
    }

    @Test
    fun `development wrapper starts java with an argument file`() {
        val launcher = HostLauncher.Development(Path.of("/jdk/bin/java"), "/a.jar:/b c.jar")

        val script = HostLauncherScript.script(launcher, socket, false, Path.of("/data/host/x.args"))
        val arguments = HostLauncherScript.argumentFile(launcher)

        assertTrue("exec '/jdk/bin/java' '@/data/host/x.args' '/Users/o'\\''brien" in script, script)
        assertTrue("JAVA_TOOL_OPTIONS" !in script)
        assertTrue("\"-cp\"\n\"/a.jar:/b c.jar\"\n\"${HostLauncherScript.HOST_MAIN}\"" in arguments, arguments)
        assertTrue(arguments.startsWith("\"-Xmx96m\""))
        assertEquals("heartbeat-chromium-host-dev.bat", HostLauncherScript.fileName(launcher, isWindows = true))
    }

    @Test
    fun `windows wrapper doubles percent signs and forwards browser arguments`() {
        val launcher = HostLauncher.Packaged(Path.of("C:\\Program Files\\Heartbeat 100%\\io.aequicor.exe"))

        val script = HostLauncherScript.script(launcher, Path.of("C:\\Users\\me\\bridge.sock"), isWindows = true)

        assertTrue(script.startsWith("@echo off\r\n"))
        assertTrue("set \"JAVA_TOOL_OPTIONS=-Xmx96m" in script)
        assertTrue(
            "\"C:\\Program Files\\Heartbeat 100%%\\io.aequicor.exe\" \"--heartbeat-chromium-host\" " +
                "\"C:\\Users\\me\\bridge.sock\" %*\r\n" in script,
            script,
        )
    }

    @Test
    fun `values that cmd cannot quote are refused`() {
        val launcher = HostLauncher.Packaged(Path.of("C:\\a\"b\\io.aequicor.exe"))

        assertFailsWith<IllegalArgumentException> {
            HostLauncherScript.script(launcher, Path.of("C:\\s.sock"), isWindows = true)
        }
    }
}
