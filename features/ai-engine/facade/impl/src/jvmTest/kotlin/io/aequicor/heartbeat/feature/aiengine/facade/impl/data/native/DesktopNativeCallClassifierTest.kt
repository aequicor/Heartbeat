package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.native

import io.aequicor.heartbeat.core.common.DispatcherProvider
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class DesktopNativeCallClassifierTest {
    @Test
    fun `pinned edits must resolve inside workspace and outside git metadata`() = runTest {
        val classifier = classifier()
        val root = Files.createTempDirectory("native-classification").toRealPath()
        try {
            Files.createDirectories(root.resolve("src"))
            Files.createDirectories(root.resolve(".git"))
            assertTrue(classifier.isWorkspaceEdit(root.resolve("src/new.kt").toString(), root.toString()))
            for (path in listOf(
                "src/new.kt",
                "@${root.resolve("new.kt")}",
                "file://${root.resolve("new.kt")}",
                root.resolve(".git/config").toString(),
                root.resolve(".GiT/config").toString(),
                root.resolve("src/../new.kt").toString(),
                root.resolve("../sibling.kt").toString(),
                root.resolve("no\u00a0break.kt").toString(),
            )) {
                assertFalse(classifier.isWorkspaceEdit(path, root.toString()), path)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `symbolic links cannot hide an outside target or git metadata`() = runTest {
        // Windows commonly denies creating links to unprivileged tests; ordinary path cases still run there.
        if (System.getProperty("os.name").startsWith("Windows")) return@runTest
        val classifier = classifier()
        val root = Files.createTempDirectory("native-links").toRealPath()
        val outside = Files.createTempDirectory("native-outside").toRealPath()
        try {
            Files.createDirectories(root.resolve("src"))
            Files.createDirectories(root.resolve("metadata"))
            Files.createSymbolicLink(root.resolve("external"), outside)
            Files.createSymbolicLink(root.resolve(".git"), root.resolve("metadata"))
            Files.createSymbolicLink(root.resolve("alias"), root.resolve(".git"))
            assertFalse(classifier.isWorkspaceEdit(root.resolve("external/new.kt").toString(), root.toString()))
            assertFalse(classifier.isWorkspaceEdit(root.resolve(".git/config").toString(), root.toString()))
            assertFalse(classifier.isWorkspaceEdit(root.resolve("alias/config").toString(), root.toString()))
        } finally {
            root.toFile().deleteRecursively()
            outside.toFile().deleteRecursively()
        }
    }

    @Test
    fun `host termination scans executable positions and nested wrappers without interpreting search data`() = runTest {
        val classifier = classifier()
        for (command in listOf(
            "./gradlew --stop", "gradle -stop", "JAVA_HOME=/jdk sudo ./gradlew --stop",
            "taskkill /F /IM java.exe", "Stop-Process -Id 4008 -Force", "/bin/kill -9 1", "shutdown /s /t 0",
            "cmd /c \"gradlew.bat --stop\"", "powershell -NoProfile -Command \"& .\\gradlew.ps1 --stop\"",
            "ls -la | pkill -f Heartbeat", "bash -c 'killall java'", "sudo ".repeat(40) + "echo harmless",
        )) {
            assertTrue(classifier.terminatesHost(command), command)
        }
        for (command in listOf(
            "git grep taskkill",
            "Select-String -Pattern Stop-Process",
            "echo 'gradlew --stop'",
            "./gradlew test",
            "printf '%s' kill",
            "bash -c 'git grep shutdown'",
        )) {
            assertFalse(classifier.terminatesHost(command), command)
        }
    }

    private fun TestScope.classifier(): DesktopNativeCallClassifier {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return DesktopNativeCallClassifier(object : DispatcherProvider {
            override val io = dispatcher
            override val default = dispatcher
            override val main = dispatcher
        })
    }
}
