package io.aequicor.heartbeat.buildlogic

import org.gradle.testfixtures.ProjectBuilder
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.jar.JarInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrepareReleaseRuntimeJarsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `five release hosts retain matching native payload and checksum with application resources`() {
        for ((target, hostEntries) in hosts) {
            val task = fixture(target)
            val jar = writeJar(task.inputDirectory.file("app.jar").get().asFile, payload)
            val original = jar.readBytes()

            task.prepare()

            val output = task.outputDirectory.file("app.jar").get().asFile
            val retained = hostEntries.flatMap { listOf(it, "$it.sha256") }.toSet() + common.keys
            assertEquals(retained, contents(output).keys, target.toString())
            for ((name, content) in contents(output)) assertEquals(payload.getValue(name), content, name)
            assertContentEquals(original, jar.readBytes(), "Source classpath is never modified")
            assertTrue(output.length() < jar.length())
        }
    }

    @Test
    fun `missing host family retains all its binaries and unknown resources survive`() {
        val task = fixture("windows" to "arm64")
        val sqlite = hosts.values.flatMap { it }.filter { it.startsWith("natives/") }.toSet()
        val missingHost = payload - "natives/windows_arm64/sqliteJni.dll"
        writeJar(task.inputDirectory.file("app.jar").get().asFile, missingHost)

        task.prepare()

        val output = contents(task.outputDirectory.file("app.jar").get().asFile)
        assertTrue((sqlite - "natives/windows_arm64/sqliteJni.dll").all { it in output })
        assertTrue("natives/windows_arm64/sqliteJni.dll.sha256" in output)
        assertTrue(common.keys.all { it in output })
        assertFalse("libskiko-macos-x64.dylib" in output)
    }

    @Test
    fun `unmodified JCEF jar signed archives and unsupported hosts remain byte for byte intact`() {
        val task = fixture()
        val inputs = mutableListOf(
            writeJar(task.inputDirectory.file("jcef-natives-host.jar").get().asFile, mapOf("native.tar.gz" to "JCEF")),
        )
        for (signature in listOf("VENDOR.SF", "VENDOR.RSA", "vendor.dsa", "VENDOR.EC", "SIG-VENDOR")) {
            inputs += writeJar(
                task.inputDirectory.file("signed-$signature.jar").get().asFile,
                payload +
                    ("META-INF/$signature" to "signature"),
            )
        }
        task.prepare()
        inputs.forEach {
            assertContentEquals(
                it.readBytes(),
                task.outputDirectory.file(it.name).get().asFile.readBytes(),
            )
        }

        for (target in listOf("linux" to "arm64", "macos" to "riscv64", "unknown" to "x64")) {
            val unsupported = fixture(target)
            val jar = writeJar(unsupported.inputDirectory.file("app.jar").get().asFile, payload)
            unsupported.prepare()
            assertContentEquals(jar.readBytes(), unsupported.outputDirectory.file("app.jar").get().asFile.readBytes())
        }
    }

    @Test
    fun `staging removes stale outputs and archive bytes are reproducible`() {
        val task = fixture()
        val source = task.inputDirectory.file("app.jar").get().asFile
        writeJar(source, payload, timestamp = 1_700_000_000_000)
        task.prepare()
        val first = task.outputDirectory.file("app.jar").get().asFile.readBytes()
        val stale = task.outputDirectory.file("stale.jar").get().asFile.apply { writeText("old payload") }
        writeJar(source, payload.entries.reversed().associate { it.toPair() }, timestamp = 1_800_000_000_000)

        task.prepare()

        assertFalse(stale.exists())
        assertContentEquals(first, task.outputDirectory.file("app.jar").get().asFile.readBytes())
    }

    @Test
    fun `manifest remains discoverable by streaming jar readers before alphabetically earlier classes`() {
        val task = fixture()
        val entries = payload + mapOf(
            "A.class" to "class preceding META-INF alphabetically",
            "META-INF/" to "",
            "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\r\nImplementation-Version: preserved\r\n\r\n",
        )
        writeJar(task.inputDirectory.file("app.jar").get().asFile, entries)

        task.prepare()

        val output = task.outputDirectory.file("app.jar").get().asFile
        JarInputStream(output.inputStream()).use { jar ->
            assertEquals("preserved", jar.manifest?.mainAttributes?.getValue("Implementation-Version"))
        }
        assertEquals("license", contents(output).getValue("META-INF/LICENSE.txt"))
    }

    @Test
    fun `overlapping directories are rejected before sync can delete any input`() {
        for (overlap in listOf("same", "output-child", "input-child")) {
            val task = fixture()
            val input = task.inputDirectory.get().asFile
            when (overlap) {
                "same" -> task.outputDirectory.set(input)
                "output-child" -> task.outputDirectory.set(input.resolve("child"))
                else -> task.outputDirectory.set(input.parentFile)
            }
            val sentinel = input.resolve("keep.txt").apply { writeText("keep") }
            assertFailsWith<IllegalArgumentException> { task.prepare() }
            assertEquals("keep", sentinel.readText())
        }
    }

    @Test
    fun `corrupt archives fail staging instead of silently dropping payload`() {
        val task = fixture()
        task.inputDirectory.file("broken.jar").get().asFile.writeText("not a jar")
        assertFailsWith<ZipException> { task.prepare() }
    }

    @Test
    fun `Gradle caches staging per target and reuses configuration cache`() {
        val root = temporaryFolder.newFolder()
        writeJar(root.resolve("input/app.jar"), payload)
        root.resolve("settings.gradle").writeText(
            "rootProject.name = 'release-runtime-test'\nbuildCache { local { directory = file('cache') } }\n",
        )
        root.resolve("build.gradle").writeText(
            """
            import io.aequicor.heartbeat.buildlogic.PrepareReleaseRuntimeJars
            plugins { id 'heartbeat.kmp.wasm' apply false }
            tasks.register('stageRuntime', PrepareReleaseRuntimeJars) {
                inputDirectory.set(layout.projectDirectory.dir('input'))
                outputDirectory.set(layout.buildDirectory.dir('runtime'))
                targetOs.set('macos')
                targetArch.set(providers.gradleProperty('targetArch').orElse('arm64'))
            }
            """.trimIndent(),
        )
        val runner = GradleRunner.create().withProjectDir(root).withPluginClasspath()
        val arguments = listOf("stageRuntime", "--build-cache", "--configuration-cache", "--offline", "--stacktrace")
        val first = runner.withArguments(arguments).build()
        val repeat = runner.build()
        assertEquals(TaskOutcome.SUCCESS, first.task(":stageRuntime")?.outcome, first.output)
        assertEquals(TaskOutcome.UP_TO_DATE, repeat.task(":stageRuntime")?.outcome, repeat.output)
        assertTrue(repeat.output.contains("Configuration cache entry reused."), repeat.output)
        val changed = runner.withArguments(arguments + "-PtargetArch=x64").build()
        assertEquals(TaskOutcome.SUCCESS, changed.task(":stageRuntime")?.outcome, changed.output)
        assertTrue("libskiko-macos-x64.dylib" in contents(root.resolve("build/runtime/app.jar")))
        assertFalse("libskiko-macos-arm64.dylib" in contents(root.resolve("build/runtime/app.jar")))
        val restored = runner.withArguments(arguments).build()
        assertEquals(TaskOutcome.FROM_CACHE, restored.task(":stageRuntime")?.outcome, restored.output)
        assertTrue("libskiko-macos-arm64.dylib" in contents(root.resolve("build/runtime/app.jar")))
    }

    private fun fixture(target: Pair<String, String> = "macos" to "arm64"): PrepareReleaseRuntimeJars {
        val root = temporaryFolder.newFolder()
        val project = ProjectBuilder.builder().withProjectDir(root).build()
        return project.tasks.create("stageRuntime", PrepareReleaseRuntimeJars::class.java).apply {
            inputDirectory.set(root.resolve("input").apply { mkdirs() })
            outputDirectory.set(root.resolve("output"))
            targetOs.set(target.first)
            targetArch.set(target.second)
        }
    }

    private fun writeJar(file: File, entries: Map<String, String>, timestamp: Long = 1_700_000_000_000): File {
        file.parentFile.mkdirs()
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name).apply { time = timestamp })
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    private fun contents(file: File): Map<String, String> = ZipFile(file).use { zip ->
        zip.entries().toList().associate { entry ->
            entry.name to zip.getInputStream(
                entry,
            ).reader().use { it.readText() }
        }
    }

    private companion object {
        val hosts = mapOf(
            ("macos" to "arm64") to listOf(
                "libskiko-macos-arm64.dylib",
                "com/sun/jna/darwin-aarch64/libjnidispatch.jnilib",
                "natives/osx_arm64/libsqliteJni.dylib",
            ),
            ("macos" to "x64") to listOf(
                "libskiko-macos-x64.dylib",
                "com/sun/jna/darwin-x86-64/libjnidispatch.jnilib",
                "natives/osx_x64/libsqliteJni.dylib",
            ),
            ("windows" to "arm64") to listOf(
                "skiko-windows-arm64.dll",
                "com/sun/jna/win32-aarch64/jnidispatch.dll",
                "natives/windows_arm64/sqliteJni.dll",
            ),
            ("windows" to "x64") to listOf(
                "skiko-windows-x64.dll",
                "com/sun/jna/win32-x86-64/jnidispatch.dll",
                "natives/windows_x64/sqliteJni.dll",
            ),
            ("linux" to "x64") to listOf(
                "libskiko-linux-x64.so",
                "com/sun/jna/linux-x86-64/libjnidispatch.so",
                "natives/linux_x64/libsqliteJni.so",
            ),
        )
        val common = mapOf(
            "app/Main.class" to "class bytes",
            "META-INF/LICENSE.txt" to "license",
            "icudtl.dat" to "Skiko shared ICU data",
            "libskiko-future-x64.so" to "unknown Skiko resource",
            "com/sun/jna/future-x64/libjnidispatch.so" to "unknown JNA resource",
            "natives/future_x64/libsqliteJni.so" to "unknown SQLite resource",
            "natives/linux_x64/another.so" to "unrelated native",
        )
        val extraNatives = listOf(
            "libskiko-linux-arm64.so",
            "com/sun/jna/aix-ppc64/libjnidispatch.a",
            "com/sun/jna/win32-x86/jnidispatch.dll",
            "natives/linux_arm64/libsqliteJni.so",
        )
        val payload = common + (hosts.values.flatten() + extraNatives)
            .flatMap { listOf(it, "$it.sha256") }.associateWith { "bytes of $it" }
    }
}
