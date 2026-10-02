package io.aequicor.heartbeat.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import java.io.File
import javax.inject.Inject

/**
 * Compiles a Windows installer from a jpackage app image with the Inno Setup command-line compiler (`ISCC.exe`).
 *
 * [script] receives the build values as preprocessor defines: `AppImageDir`, `AppExeName`, `AppVersion`,
 * `AppPublisher`, `AppDescription`, `AppArchitecture`, `SetupIconFile` and `WizardSmallImageFile`
 * (comma-separated; Setup picks the size that best fits the display scale). The installer is written to
 * [outputDirectory] as `<outputBaseName>.exe`; installers left there by previous builds are removed first.
 *
 * The compiler is taken from [innoSetupDirectory] when it is set, otherwise from the newest default Inno Setup
 * install folder (`Program Files`, `Program Files (x86)`, per-user `%LOCALAPPDATA%\Programs`), then from `PATH`.
 */
@DisableCachingByDefault(because = "Compresses the whole app image into a large single-use installer")
abstract class PackageInnoSetup : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val script: RegularFileProperty

    /** jpackage app image root: the launcher next to its `app` and `runtime` folders. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val appImage: DirectoryProperty

    /** Launcher file name inside [appImage], e.g. `io.aequicor.exe`. */
    @get:Input abstract val appExecutable: Property<String>

    @get:Input abstract val appVersion: Property<String>

    @get:Input abstract val appPublisher: Property<String>

    @get:Input abstract val appDescription: Property<String>

    /** Inno Setup architecture identifier the installer runs on in 64-bit mode: `x64compatible` or `arm64`. */
    @get:Input abstract val architecture: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val setupIcon: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val wizardSmallImages: ConfigurableFileCollection

    /** Inno Setup install folder holding `ISCC.exe`; when unset, the default locations are searched. */
    @get:Internal abstract val innoSetupDirectory: DirectoryProperty

    @get:Input abstract val outputBaseName: Property<String>

    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @get:Inject abstract val execOperations: ExecOperations

    @get:Inject abstract val files: FileSystemOperations

    @TaskAction
    fun compile() {
        val compiler = compiler()
        logger.lifecycle("Inno Setup compiler: $compiler")
        val image = appImage.get().asFile
        check(image.resolve(appExecutable.get()).isFile) { "App image $image has no launcher ${appExecutable.get()}" }
        files.delete { delete(outputDirectory.asFileTree.matching { include("*.exe") }) }
        val defines = mapOf(
            "AppImageDir" to image.absolutePath,
            "AppExeName" to appExecutable.get(),
            "AppVersion" to appVersion.get(),
            "AppPublisher" to appPublisher.get(),
            "AppDescription" to appDescription.get(),
            "AppArchitecture" to architecture.get(),
            "SetupIconFile" to setupIcon.get().asFile.absolutePath,
            "WizardSmallImageFile" to wizardSmallImages.files.joinToString(",") { it.absolutePath },
        )
        val output = outputDirectory.get().asFile
        execOperations.exec {
            executable = compiler.absolutePath
            args("/Qp")
            defines.forEach { (name, value) -> args("/D$name=$value") }
            args("/O${output.absolutePath}", "/F${outputBaseName.get()}", script.get().asFile.absolutePath)
        }
        logger.lifecycle("Windows installer: ${output.resolve(outputBaseName.get() + ".exe")}")
    }

    private fun compiler(): File {
        innoSetupDirectory.orNull?.asFile?.let { directory ->
            val configured = directory.resolve(COMPILER)
            check(configured.isFile) { "$COMPILER is not found in the configured Inno Setup folder $directory" }
            return configured
        }
        val installRoots = listOf("ProgramFiles", "ProgramFiles(x86)").mapNotNull(System::getenv).map(::File) +
            listOfNotNull(System.getenv("LOCALAPPDATA")?.let { File(it, "Programs") })
        // Install folders end with the major version ("Inno Setup 7"): the newest installed major wins.
        val installed = installRoots
            .flatMap { root -> root.listFiles().orEmpty().asList() }
            .filter { it.isDirectory && it.name.startsWith(INSTALL_FOLDER, ignoreCase = true) }
            .sortedByDescending { it.name.drop(INSTALL_FOLDER.length).trim().toIntOrNull() ?: 0 }
        // Windows accepts quoted PATH entries.
        val onPath = System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .map { it.trim().trim('"') }
            .filter(String::isNotBlank)
            .map(::File)
        return checkNotNull((installed + onPath).map { it.resolve(COMPILER) }.firstOrNull(File::isFile)) {
            "Inno Setup 6.6 or newer is required to build the Windows installer: install it " +
                "(winget install JRSoftware.InnoSetup.7) or point the heartbeat.innoSetupDir Gradle property " +
                "to the folder with $COMPILER"
        }
    }

    private companion object {
        const val COMPILER = "ISCC.exe"
        const val INSTALL_FOLDER = "Inno Setup"
    }
}
