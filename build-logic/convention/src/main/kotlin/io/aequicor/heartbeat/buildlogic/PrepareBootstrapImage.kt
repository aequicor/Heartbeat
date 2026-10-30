package io.aequicor.heartbeat.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject

/**
 * Stages the complete verified JVM image with an explicit HeartbeatJvm executable/config alias. macOS reseals only
 * the outer bundle afterwards, preserving the existing executable metadata and leaving runtime bytes unchanged.
 * Splitting this complete image then preserves its resource seal across runtime/platform/seed archives; the original
 * full image stays available for packaged checks and fallback.
 */
abstract class PrepareBootstrapImage : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceImage: DirectoryProperty

    @get:Input abstract val os: Property<String>
    @get:Input abstract val appName: Property<String>

    @get:OutputDirectory abstract val destination: DirectoryProperty

    @get:Inject abstract val files: FileSystemOperations
    @get:Inject abstract val commands: ExecOperations

    @TaskAction
    fun prepare() {
        val source = sourceImage.get().asFile.toPath().toAbsolutePath().normalize()
        val target = destination.get().asFile.toPath().toAbsolutePath().normalize()
        require(!source.startsWith(target) && !target.startsWith(source)) { "Bootstrap staging must not overlap its source" }
        files.sync {
            from(sourceImage)
            into(destination)
        }
        if (os.get() != "windows") {
            Files.walk(source).use { paths ->
                paths.forEach { path ->
                    Files.setPosixFilePermissions(
                        target.resolve(source.relativize(path)), Files.getPosixFilePermissions(path),
                    )
                }
            }
        }
        val binary = when (os.get()) {
            "macos" -> "Contents/MacOS/${appName.get()}"
            "linux" -> "bin/${appName.get()}"
            else -> "${appName.get()}.exe"
        }
        val config = when (os.get()) {
            "macos" -> "Contents/app/${appName.get()}.cfg"
            "linux" -> "lib/app/${appName.get()}.cfg"
            else -> "app/${appName.get()}.cfg"
        }
        val executable = target.resolve(binary)
        val cfg = target.resolve(config)
        val aliasName = if (os.get() == "windows") "HeartbeatJvm.exe" else "HeartbeatJvm"
        Files.copy(executable, executable.resolveSibling(aliasName), StandardCopyOption.COPY_ATTRIBUTES)
        Files.copy(cfg, cfg.resolveSibling("HeartbeatJvm.cfg"), StandardCopyOption.COPY_ATTRIBUTES)
        if (os.get() == "macos") {
            // The copied main executable's signature binds Info.plist. As a helper alias it needs a standalone seal.
            commands.exec {
                commandLine(
                    "/usr/bin/codesign", "--force", "--sign", "-",
                    "--preserve-metadata=identifier,entitlements,flags,runtime",
                    executable.resolveSibling(aliasName).toString(),
                )
            }
            commands.exec {
                commandLine(
                    "/usr/bin/codesign", "--force", "--sign", "-",
                    "--preserve-metadata=identifier,entitlements,requirements,flags,runtime", target.toString(),
                )
            }
            commands.exec {
                commandLine("/usr/bin/codesign", "--verify", "--deep", "--strict", target.toString())
            }
        }
    }
}
