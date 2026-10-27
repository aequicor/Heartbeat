package io.aequicor.heartbeat.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.inject.Inject

/**
 * Stages ProGuard's release jars for packaging, keeping only the host's known Skiko, JNA and SQLite native
 * resources. Names, classes, licenses and host checksums survive unchanged; the development classpath is not touched.
 * [targetOs] is `macos`, `windows` or `linux`; [targetArch] is `x64` or `arm64` (Linux releases support x64).
 *
 * Unknown platforms/resources, signed jars and native families missing their host binary are preserved. Jars with
 * no removable entries are copied byte for byte. When [deferredJcefPlatform] is set, the host's JCEF native archive
 * is omitted for download on first use; all bridge classes stay on the packaged classpath. Original JCEF artifacts
 * are still checked by `verifyBrowserRuntime` before packaging. [deferredJarPrefixes] removes explicitly pinned
 * worker dependencies that their feature downloads and verifies on first use. Rewritten jars use
 * sorted entries and a fixed ZIP timestamp so cache results do not depend on the input archive's timestamps.
 */
@CacheableTask
abstract class PrepareReleaseRuntimeJars : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputDirectory: DirectoryProperty

    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @get:Input abstract val targetOs: Property<String>

    @get:Input abstract val targetArch: Property<String>

    /** Empty keeps JCEF bundled; otherwise exactly one archive for this jcefmaven platform must be removed. */
    @get:Input abstract val deferredJcefPlatform: Property<String>

    /** Exact artifact/version prefixes of worker-only JARs. Each must identify exactly one staged JAR. */
    @get:Input abstract val deferredJarPrefixes: ListProperty<String>

    @get:Inject abstract val files: FileSystemOperations

    init {
        deferredJcefPlatform.convention("")
        deferredJarPrefixes.convention(emptyList())
    }

    /** Replaces the staging directory with the release payload, then filters eligible native resource families. */
    @TaskAction
    fun prepare() {
        val source = inputDirectory.get().asFile.canonicalFile.toPath()
        val destination = outputDirectory.get().asFile.canonicalFile.toPath()
        require(!source.startsWith(destination) && !destination.startsWith(source)) {
            "Release runtime input and output directories must be separate and not nested"
        }
        files.sync {
            from(inputDirectory)
            into(outputDirectory)
        }
        val families = nativeFamilies(targetOs.get(), targetArch.get())
        val jars = destination.toFile().walkTopDown().filter { it.isFile && it.extension == "jar" }.toList()
        val deferredArchive = findDeferredJcefArchive(jars)
        val deferredJars = findDeferredJars(jars)
        if (families.isEmpty() && deferredArchive == null && deferredJars.isEmpty()) return
        var removedBytes = 0L
        for (jar in jars) {
            val originalSize = jar.length()
            if (jar in deferredJars) {
                Files.delete(jar.toPath())
            } else {
                prune(jar, families, deferredArchive)
            }
            removedBytes += originalSize - jar.length()
        }
        logger.lifecycle(
            "Release runtime jars: saved {} bytes after filtering native and optional resources",
            removedBytes,
        )
    }

    private fun findDeferredJars(jars: List<File>): Set<File> = deferredJarPrefixes.get().map { prefix ->
        require(prefix.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*-"))) { "Invalid deferred artifact prefix" }
        // ProGuard output names carry a suffix after the prefix; unshrunk platform jars are exactly <artifact>-<version>.jar.
        val matches = jars.filter { it.name.startsWith(prefix) || it.name == prefix.dropLast(1) + ".jar" }
        check(matches.size == 1) { "Expected one deferred artifact $prefix, found ${matches.size}" }
        matches.single()
    }.toSet()

    private fun findDeferredJcefArchive(jars: List<File>): String? {
        val platform = deferredJcefPlatform.get()
        if (platform.isEmpty()) return null
        require(platform in setOf("macosx-arm64", "macosx-amd64", "windows-arm64", "windows-amd64", "linux-amd64")) {
            "Unsupported deferred JCEF platform: $platform"
        }
        val candidates = jars.flatMap { jar ->
            ZipFile(jar).use { archive ->
                val names = archive.entries().toList().map { it.name }
                val matches = names.filter {
                    it.startsWith("jcef-natives-$platform-") && it.endsWith(".tar.gz") && '/' !in it
                }
                check(matches.isEmpty() || names.none(::isSignature)) { "Cannot defer JCEF from a signed jar: $jar" }
                matches
            }
        }
        check(candidates.size == 1) { "Expected one JCEF archive for $platform, found ${candidates.size}" }
        return candidates.single()
    }

    private fun prune(jar: File, families: List<NativeFamily>, deferredArchive: String?) {
        val temporary = ZipFile(jar).use { input ->
            val entries = input.entries().toList()
            if (entries.any { isSignature(it.name) }) return
            val names = entries.map { it.name }.toSet()
            val removed = families.filter { it.host in names }.flatMap { family ->
                (family.paths - family.host).flatMap { listOf(it, "$it.sha256") }
            }.toSet() + listOfNotNull(deferredArchive)
            if (names.none { it in removed }) return
            val temporary = Files.createTempFile(jar.parentFile.toPath(), jar.name, ".tmp")
            ZipOutputStream(Files.newOutputStream(temporary)).use { output ->
                output.setComment(input.comment)
                val retained = entries.filterNot { it.name in removed }.sortedWith(
                    compareBy<ZipEntry> {
                        when (it.name) {
                            "META-INF/" -> 0
                            "META-INF/MANIFEST.MF" -> 1
                            else -> 2
                        }
                    }.thenBy { it.name },
                )
                for (entry in retained) {
                    output.putNextEntry(
                        ZipEntry(entry.name).apply {
                            setTimeLocal(LocalDateTime.of(1980, 1, 1, 0, 0))
                            comment = entry.comment
                        },
                    )
                    input.getInputStream(entry).use { it.copyTo(output) }
                    output.closeEntry()
                }
            }
            temporary
        }
        // Close the input before replacing the file: Windows locks open archives.
        Files.move(temporary, jar.toPath(), REPLACE_EXISTING)
    }

    private fun isSignature(name: String): Boolean {
        val upper = name.uppercase(java.util.Locale.ROOT)
        if (!upper.startsWith("META-INF/")) return false
        val base = upper.removePrefix("META-INF/")
        return '/' !in base && (base.startsWith("SIG-") || listOf(".SF", ".RSA", ".DSA", ".EC").any(base::endsWith))
    }
}

private data class NativeFamily(val host: String, val paths: Set<String>)

private fun nativeFamilies(os: String, arch: String): List<NativeFamily> {
    if (arch !in setOf("x64", "arm64") || os !in setOf("macos", "windows", "linux")) {
        return emptyList()
    }
    if (os == "linux" && arch != "x64") {
        return emptyList()
    }
    val skikoPaths = listOf("macos", "windows", "linux").flatMap { platform ->
        listOf("x64", "arm64").map { cpu -> skikoPath(platform, cpu) }
    }.toSet()
    val jnaPlatforms = mapOf(
        "win32" to listOf("x86", "x86-64", "aarch64"),
        "darwin" to listOf("x86-64", "aarch64"),
        "linux" to listOf(
            "x86", "x86-64", "arm", "armel", "aarch64", "ppc", "ppc64le", "mips64el", "loongarch64", "s390x", "riscv64",
        ),
        "aix" to listOf("ppc", "ppc64"),
        "sunos" to listOf("x86", "x86-64", "sparc", "sparcv9"),
        "dragonflybsd" to listOf("x86-64"),
        "freebsd" to listOf("x86", "x86-64", "aarch64"),
        "openbsd" to listOf("x86-64"),
    )
    val jnaOs = when (os) {
        "macos" -> "darwin"
        "windows" -> "win32"
        else -> os
    }
    val sqliteOs = if (os == "macos") "osx" else os
    return listOf(
        NativeFamily(skikoPath(os, arch), skikoPaths),
        NativeFamily(
            jnaPath(jnaOs, if (arch == "arm64") "aarch64" else "x86-64"),
            jnaPlatforms.flatMap { (platform, cpus) -> cpus.map { jnaPath(platform, it) } }.toSet(),
        ),
        NativeFamily(
            sqlitePath(sqliteOs, arch),
            listOf("osx", "windows", "linux").flatMap { platform ->
                listOf("x64", "arm64").map { sqlitePath(platform, it) }
            }.toSet(),
        ),
    )
}

private fun skikoPath(os: String, arch: String): String = when (os) {
    "macos" -> "libskiko-macos-$arch.dylib"
    "windows" -> "skiko-windows-$arch.dll"
    else -> "libskiko-linux-$arch.so"
}

private fun jnaPath(os: String, arch: String): String {
    val name = when (os) {
        "win32" -> "jnidispatch.dll"
        "darwin" -> "libjnidispatch.jnilib"
        "aix" -> "libjnidispatch.a"
        else -> "libjnidispatch.so"
    }
    return "com/sun/jna/$os-$arch/$name"
}

private fun sqlitePath(os: String, arch: String): String {
    val name = when (os) {
        "osx" -> "libsqliteJni.dylib"
        "windows" -> "sqliteJni.dll"
        else -> "libsqliteJni.so"
    }
    return "natives/${os}_$arch/$name"
}
