package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PiHostCommandsTest {
    @Test
    fun `stopping the gradle daemons ends the host however the command is wrapped`() {
        assertTrue(terminatesHost(command(""".\gradlew.bat --stop""")))
        assertTrue(terminatesHost(command("./gradlew --stop")))
        assertTrue(terminatesHost(command("gradle -stop")))
        assertTrue(terminatesHost(command("""cd C:\repo; .\gradlew.bat --stop 2>&1 | Select-Object -Last 3""")))
        assertTrue(terminatesHost(command("cmd /c \"gradlew.bat --stop\"")))
        assertTrue(terminatesHost(command("powershell -NoProfile -Command \"& .\\gradlew.ps1 --stop\"")))
        assertTrue(terminatesHost(command("JAVA_HOME=/jdk sudo ./gradlew --stop")))
        assertTrue(terminatesHost(command("""C:\repo\gradlew.bat --stop""")))
    }

    @Test
    fun `process killers and power commands end the host`() {
        assertTrue(terminatesHost(command("taskkill /F /IM java.exe")))
        assertTrue(terminatesHost(command("""C:\Windows\System32\taskkill.exe /F /PID 4008""")))
        assertTrue(terminatesHost(command("Stop-Process -Id 4008 -Force")))
        assertTrue(terminatesHost(command("ls -la | pkill -f Heartbeat")))
        assertTrue(terminatesHost(command("/bin/kill -9 1")))
        assertTrue(terminatesHost(command("shutdown /s /t 0")))
        assertTrue(terminatesHost(command("killall java")))
    }

    @Test
    fun `commands that only mention a killer or build with gradle are left to trust`() {
        assertFalse(terminatesHost(command("git grep -n \"taskkill|Stop-Process\" -- \"*.kt\"")))
        assertFalse(terminatesHost(command("Select-String -Path build.gradle.kts -Pattern 'kill'")))
        assertFalse(terminatesHost(command(""".\gradlew.bat :features:ai-engine:pi:impl:jvmTest --console=plain""")))
        assertFalse(terminatesHost(command("./gradlew detekt --continue 2>&1 | tail -n 20")))
        assertFalse(terminatesHost(command("npm run kill-server")))
        assertFalse(terminatesHost(command("echo kill")))
        assertFalse(terminatesHost(command("ls -la")))
        assertFalse(terminatesHost(command("")))
    }

    @Test
    fun `a file edit target is a path and never a command`() {
        assertFalse(terminatesHost(PiApprovalCall("write", "/tmp/kill", "/tmp/kill")))
        assertFalse(terminatesHost(PiApprovalCall("edit", """C:\repo\kill""", """C:\repo\kill""")))
        assertTrue(terminatesHost(PiApprovalCall("bash", "kill -9 1")))
    }

    @Test
    fun `no trust level answers a command that stops the host`() {
        val call = command(""".\gradlew.bat --stop""")
        TrustLevel.entries.forEach { level -> assertFalse(level.answers(call, null), "$level answered it") }
    }

    @Test
    fun `trust still answers the calls it covers`() {
        assertTrue(TrustLevel.Full.answers(command("./gradlew jvmTest"), null))
        assertFalse(TrustLevel.Ask.answers(command("ls -la"), null))
    }

    @Test
    fun `the request states what stopping the host costs`() {
        val stopping = approvalRequest("ui-1", TurnId("t1"), command(""".\gradlew.bat --stop"""))
        assertEquals("Завершить Heartbeat и выполнить", requireNotNull(stopping).options.first().title)
        val ordinary = approvalRequest("ui-2", TurnId("t1"), command("ls -la"))
        assertEquals("Разрешить", requireNotNull(ordinary).options.first().title)
    }

    private fun command(target: String) = PiApprovalCall("powershell", target)
}
