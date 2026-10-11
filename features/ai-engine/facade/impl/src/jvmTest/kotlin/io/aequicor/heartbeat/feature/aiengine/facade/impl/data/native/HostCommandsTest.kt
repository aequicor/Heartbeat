package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.native

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HostCommandsTest {
    @Test
    fun `stopping the gradle daemons ends the host however the command is wrapped`() {
        assertTrue(terminatesHostCommand(command(""".\gradlew.bat --stop""")))
        assertTrue(terminatesHostCommand(command("./gradlew --stop")))
        assertTrue(terminatesHostCommand(command("gradle -stop")))
        assertTrue(terminatesHostCommand(command("""cd C:\repo; .\gradlew.bat --stop 2>&1 | Select-Object -Last 3""")))
        assertTrue(terminatesHostCommand(command("cmd /c \"gradlew.bat --stop\"")))
        assertTrue(terminatesHostCommand(command("powershell -NoProfile -Command \"& .\\gradlew.ps1 --stop\"")))
        assertTrue(terminatesHostCommand(command("JAVA_HOME=/jdk sudo ./gradlew --stop")))
        assertTrue(terminatesHostCommand(command("""C:\repo\gradlew.bat --stop""")))
    }

    @Test
    fun `process killers and power commands end the host`() {
        assertTrue(terminatesHostCommand(command("taskkill /F /IM java.exe")))
        assertTrue(terminatesHostCommand(command("""C:\Windows\System32\taskkill.exe /F /PID 4008""")))
        assertTrue(terminatesHostCommand(command("Stop-Process -Id 4008 -Force")))
        assertTrue(terminatesHostCommand(command("ls -la | pkill -f Heartbeat")))
        assertTrue(terminatesHostCommand(command("/bin/kill -9 1")))
        assertTrue(terminatesHostCommand(command("shutdown /s /t 0")))
        assertTrue(terminatesHostCommand(command("killall java")))
    }

    @Test
    fun `wrapper executable paths and extensions preserve host recognition`() {
        listOf(
            """cmd.exe /c "gradlew.bat --stop"""",
            """C:\Windows\System32\cmd.exe /c "taskkill /F /PID 4008"""",
            """powershell.exe -NoProfile -Command "Stop-Process -Id 4008"""",
            """& "C:\Program Files\PowerShell\7\pwsh.exe" -Command ".\gradlew.bat --stop"""",
        ).forEach { target -> assertTrue(terminatesHostCommand(command(target)), target) }
    }

    @Test
    fun `quoted executable paths and composed shell payloads preserve host recognition`() {
        listOf(
            """& "C:\Repo With Spaces\gradlew.bat" --stop""",
            """powershell -Command "Set-Location C:\repo; .\gradlew.bat --stop"""",
            """cmd /c "cd /d C:\repo && gradlew.bat --stop"""",
            """bash -c "cd /repo && ./gradlew --stop"""",
            """powershell -ExecutionPolicy Bypass -Command "Stop-Process -Id 4008"""",
            """powershell -ExecutionPolicy Bypass -File "C:\Repo With Spaces\gradlew.ps1" --stop""",
            """sudo -u root bash -lc 'cd /repo && ./gradlew --stop'""",
        ).forEach { target -> assertTrue(terminatesHostCommand(command(target)), target) }
    }

    @Test
    fun `quoted wrapper search arguments and escaped powershell literals remain data`() {
        listOf(
            """powershell.exe -ExecutionPolicy Bypass -Command "Select-String -Pattern 'kill; Stop-Process'"""",
            """powershell -Command Select-String -Pattern "kill; Stop-Process""",
            """cmd.exe /c "git grep 'taskkill|Stop-Process'"""",
            """bash -c "git grep 'kill; shutdown'"""",
            """Write-Output "sample `"; Stop-Process -Id 1"""",
        ).forEach { target -> assertFalse(terminatesHostCommand(command(target)), target) }
    }

    @Test
    fun `cmd preserves arguments after quoted executables and double outer quotes`() {
        listOf(
            """cmd /c "gradlew.bat" --stop""",
            """cmd.exe /k "gradlew.bat" "--stop""",
            """cmd.exe /c "C:\repo\gradlew.bat" --stop""",
            """cmd /c "powershell.exe" -Command "Stop-Process -Id 4008"""",
            """cmd /c ""C:\Repo With Spaces\gradlew.bat" --stop"""",
            """cmd /c "C:\Repo With Spaces\gradlew.bat" --stop""",
            """cmd /d /s /c ""C:\Repo With Spaces\gradlew.bat" --stop"""",
            """cmd /d /s /k "".\Repo With Spaces\gradlew.bat" "--stop""""",
            """cmd /c "cd /d C:\repo && gradlew.bat" --stop""",
            """powershell -Command 'cmd /c "gradlew.bat" --stop'""",
            """cmd /c "cmd /c gradlew.bat --stop"""",
        ).forEach { target -> assertTrue(terminatesHostCommand(command(target)), target) }
    }

    @Test
    fun `cmd quoted search arguments and posix command zero are not executable payloads`() {
        listOf(
            """cmd /c "echo" "kill; Stop-Process"""",
            """cmd /d /s /c ""echo" "kill; Stop-Process""""",
            """cmd /c "git" grep "taskkill|Stop-Process"""",
            """cmd /c "cmd /c echo sample" "kill; Stop-Process"""",
            """bash -c "gradlew" --stop""",
            """sh -c "echo sample" "kill; Stop-Process"""",
        ).forEach { target -> assertFalse(terminatesHostCommand(command(target)), target) }
    }

    @Test
    fun `cmd recognises whole quoted scripts beginning with an executable path`() {
        listOf(
            """cmd /c ".\gradlew.bat --stop"""",
            """cmd /k "C:\repo\gradlew.bat --stop"""",
            """cmd /d /s /c "C:\Windows\System32\taskkill.exe /F /IM java.exe"""",
            """cmd /c ".\gradlew.bat jvmTest && C:\Windows\System32\taskkill.exe /F /IM java.exe"""",
            """cmd /c "C:\Windows\System32\cmd.exe /c gradlew.bat --stop"""",
        ).forEach { target ->
            val call = command(target)
            assertTrue(terminatesHostCommand(call), target)
        }
    }

    @Test
    fun `cmd quoted executable paths and path based search scripts keep literal arguments intact`() {
        listOf(
            """cmd /c "C:\Repo With Spaces\gradlew.bat" jvmTest""",
            """cmd /c "C:\Program Files\Git\bin\git.exe" grep "taskkill|Stop-Process"""",
            """cmd /d /s /c ""C:\Program Files\Git\bin\git.exe" grep "taskkill|Stop-Process""""",
            """cmd /c "C:\Windows\System32\findstr.exe Stop-Process *.kt"""",
            """cmd /c "C:\Windows\System32\cmd.exe /c echo sample" "kill; Stop-Process"""",
        ).forEach { target -> assertFalse(terminatesHostCommand(command(target)), target) }
    }

    @Test
    fun `commands that only mention a killer or build with gradle are left to trust`() {
        assertFalse(terminatesHostCommand(command("git grep -n \"taskkill|Stop-Process\" -- \"*.kt\"")))
        assertFalse(terminatesHostCommand(command("Select-String -Path build.gradle.kts -Pattern 'kill'")))
        assertFalse(
            terminatesHostCommand(command(""".\gradlew.bat :features:ai-engine:pi:impl:jvmTest --console=plain""")),
        )
        assertFalse(terminatesHostCommand(command("./gradlew detekt --continue 2>&1 | tail -n 20")))
        assertFalse(terminatesHostCommand(command("npm run kill-server")))
        assertFalse(terminatesHostCommand(command("echo kill")))
        assertFalse(terminatesHostCommand(command("ls -la")))
        assertFalse(terminatesHostCommand(command("")))
    }

    @Test
    fun `deep wrapper chains wait for the user without exhausting the scan stack`() {
        val call = command("exec ".repeat(2_000) + "echo sample")
        assertTrue(terminatesHostCommand(call))
    }

    private fun command(target: String) = target
}
