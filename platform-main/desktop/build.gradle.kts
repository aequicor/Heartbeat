import io.aequicor.heartbeat.buildlogic.PackageInnoSetup
import io.aequicor.heartbeat.buildlogic.PreparePiRuntime
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask
import org.jetbrains.compose.desktop.application.tasks.AbstractCheckNativeDistributionRuntime
import org.jetbrains.compose.desktop.application.tasks.AbstractJvmToolOperationTask
import org.jetbrains.compose.reload.gradle.ComposeHotRun

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.heartbeat.detekt)
}

dependencies {
    implementation(projects.platformMain.shared)
    implementation(projects.designSystem.tokens)
    implementation(projects.designSystem.theme)
    implementation(projects.designSystem.components)
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)
    implementation(libs.compose.uiToolingPreview)
    implementation(libs.jbr.api)
    implementation(libs.jna.platform)
    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// The app runtime is independent of Gradle's daemon and shared modules' compilation toolchains.
val desktopRuntime = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(libs.versions.desktop.jdk.get().toInt()))
    vendor.set(JvmVendorSpec.JETBRAINS)
}
val desktopJavaHome = desktopRuntime.map { it.metadata.installationPath.asFile.absolutePath }

// SHA-256 of each Pi release asset for the version pinned as `pi` in gradle/libs.versions.toml.
val piChecksums = mapOf(
    "windows-x64" to "aab2ba67baf8ff97a52d05b62d88e9e65a840c6ea8fa1029a28d62d210d4e5fc",
    "windows-arm64" to "2e0d544999a765018ee5c2ff1a8b1a7e0f5d5b6b1e00b32d8c025d6c1dbcc833",
    "darwin-x64" to "01d8ee28d7114fec4f4eeedbb7561f790853040e9bfbdeebe79437ab66ea51f5",
    "darwin-arm64" to "4f8d288b78c9768d3a4ac6f61f06cd34394b82ac17d5b42d1e44a437add401b7",
    "linux-x64" to "80d78dd62d50049a006b981d994c61255bcc10e730b0c278d4ea0a755909764c",
    "linux-arm64" to "364b4a9f8491450b27a4857d4e3c780dbaf696790821c176a873e860cbbc3b89",
)
val piOsName = providers.systemProperty("os.name").get()
val piArchName = providers.systemProperty("os.arch").get()
val piOs = when {
    piOsName.startsWith("Windows") -> "windows"
    piOsName.startsWith("Mac") -> "darwin"
    piOsName.startsWith("Linux") -> "linux"
    else -> null
}
val piArch = when (piArchName.lowercase()) {
    "aarch64", "arm64" -> "arm64"
    "amd64", "x86_64" -> "x64"
    else -> null
}
val piTarget = if (piOs != null && piArch != null) "$piOs-$piArch" else null
val verifyWindowRuntime = tasks.register<JavaExec>("verifyWindowRuntime") {
    group = "verification"
    description = "Checks the JBR native caption service before packaging desktop distributions."
    javaLauncher.set(desktopRuntime)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.aequicor.heartbeat.platform.desktop.WindowRuntimeProbeKt")
    val supportsCaption = piOs == "windows" || piOs == "darwin"
    onlyIf { supportsCaption }
}
if (piTarget == null) {
    logger.warn("Pi runtime is not available for $piOsName/$piArchName; the desktop app is built without it")
}
val piResourcesDir = layout.buildDirectory.dir("generated/piResources")
val preparePiRuntime = piTarget?.let { target ->
    tasks.register<PreparePiRuntime>("preparePiRuntime") {
        version.set(libs.versions.pi)
        asset.set("pi-$target." + if (piOs == "windows") "zip" else "tar.gz")
        sha256.set(piChecksums.getValue(target))
        baseUrl.set("https://github.com/earendil-works/pi/releases/download")
        offline.set(gradle.startParameter.isOffline)
        fallbackLicense.set(layout.projectDirectory.file("pi/LICENSE"))
        cacheDirectory.set(gradle.gradleUserHomeDir.resolve("caches/heartbeat/pi"))
        outputDirectory.set(piResourcesDir)
    }
}

// A plain IDE main() launch builds classes only; preparing Pi here lets that launch find it (see Main.kt).
preparePiRuntime?.let { task -> tasks.named("processResources") { dependsOn(task) } }

compose.desktop {
    application {
        mainClass = "io.aequicor.heartbeat.platform.desktop.MainKt"
        buildTypes.release.proguard {
            configurationFiles.from(
                layout.projectDirectory.file("compose-desktop.pro"),
                layout.projectDirectory.file("proguard-rules.pro"),
            )
        }
        nativeDistributions {
            // Windows: Inno Setup installer, see `packageInnoSetup` below.
            targetFormats(TargetFormat.Dmg, TargetFormat.Deb)
            // The jlink runtime holds only listed JDK modules; keep in sync with `suggestRuntimeModules`.
            // jdk.unsupported: DataStore's protobuf accesses sun.misc.Unsafe; jdk.httpserver: the loopback search bridge;
            // java.prefs and java.scripting: the PlantUML engine of the diagram worker process. Its launcher passes
            // the app's JVM options to that worker, which refuses to draw with an -Xmx above its own cap.
            modules(
                "java.instrument",
                "java.management",
                "java.prefs",
                "java.scripting",
                "jdk.httpserver",
                "jdk.unsupported",
            )
            packageName = "io.aequicor"
            packageVersion = "1.0.0"
            description = "Heartbeat AI Studio"
            vendor = "Aequicor"
            macOS {
                iconFile.set(layout.projectDirectory.file("icons/heartbeat.icns"))
                dockName = "Heartbeat"
            }
            windows {
                iconFile.set(layout.projectDirectory.file("icons/heartbeat.ico"))
            }
            linux {
                iconFile.set(layout.projectDirectory.file("icons/heartbeat.png"))
            }
            preparePiRuntime?.let { task -> appResourcesRootDir.set(task.flatMap { it.outputDirectory }) }
        }
    }
}

// Compose bundles Hot Reload; keep its launchers on the same development-only entry point as run.
tasks.withType<ComposeHotRun>().configureEach {
    mainClass.set("io.aequicor.heartbeat.platform.desktop.DevelopmentMainKt")
    // Hot Reload does not pass Compose app resources; attach the bundled Pi runtime the same way run does.
    preparePiRuntime?.let { task ->
        dependsOn(task)
        // A configuration-time path: the Hot Reload argfile task resolves JVM arguments before tasks run.
        val resourcesDir = piResourcesDir.get().dir("common").asFile.absolutePath
        systemProperty("compose.application.resources.dir", resourcesDir)
    }
}

// The default Compose build is development; release tasks retain the protected MainKt entry point.
// Configure after Compose has registered and initialized its tasks, without an environment/property escape hatch.
afterEvaluate {
    tasks.withType<AbstractJvmToolOperationTask>().configureEach {
        javaHome.set(desktopJavaHome)
    }
    tasks.withType<AbstractCheckNativeDistributionRuntime>().configureEach {
        jdkHome.set(desktopJavaHome)
    }
    tasks.withType<AbstractJPackageTask>().configureEach {
        dependsOn(verifyWindowRuntime)
        if (!name.contains("Release")) {
            launcherMainClass.set("io.aequicor.heartbeat.platform.desktop.DevelopmentMainKt")
        }
    }
    tasks.withType<JavaExec>().configureEach {
        javaLauncher.set(desktopRuntime)
        // Compose initializes an explicit executable. Gradle's non-null setter requires resolving the launcher here.
        setExecutable(desktopRuntime.get().executablePath.asFile.absolutePath)
        if (name == "run") mainClass.set("io.aequicor.heartbeat.platform.desktop.DevelopmentMainKt")
    }
}

// The Windows installer is built with Inno Setup (packaging/windows/heartbeat.iss) from the createDistributable app
// image instead of jpackage's MSI: Setup installs per user without administrator rights (all users on request), adds
// Start menu and desktop shortcuts, can start the app when it finishes, and its uninstaller removes the uninstalling
// user's app data (including bundled Pi data). macOS DMG has no uninstaller, so app data there is removed by the user.
val innoSetupArchitecture = mapOf("x64" to "x64compatible", "arm64" to "arm64")[piArch]
if (piOs == "windows" && innoSetupArchitecture != null) {
    val innoSetupDir = providers.gradleProperty("heartbeat.innoSetupDir").map(::File)
    // Compose registers its packaging tasks after evaluation.
    afterEvaluate {
        mapOf("" to "main", "Release" to "main-release").forEach { (buildType, outputName) ->
            val distributable = tasks.named<AbstractJPackageTask>("create${buildType}Distributable")
            val installer = tasks.register<PackageInnoSetup>("package${buildType}InnoSetup") {
                group = "compose desktop"
                description = "Builds the Windows installer from the ${distributable.name} app image with Inno Setup."
                dependsOn(distributable)
                script.set(layout.projectDirectory.file("packaging/windows/heartbeat.iss"))
                appImage.set(distributable.flatMap { it.destinationDir.dir(it.packageName) })
                appExecutable.set(distributable.flatMap { it.packageName }.map { "$it.exe" })
                appVersion.set(distributable.flatMap { it.packageVersion })
                appPublisher.set(distributable.flatMap { it.packageVendor })
                appDescription.set(distributable.flatMap { it.packageDescription })
                architecture.set(innoSetupArchitecture)
                setupIcon.set(distributable.flatMap { it.iconFile })
                val icons = layout.projectDirectory.dir("src/main/resources/icons")
                wizardSmallImages.from(listOf(64, 128, 256).map { icons.file("heartbeat-$it.png") })
                innoSetupDirectory.fileProvider(innoSetupDir)
                outputBaseName.set(appVersion.map { "Heartbeat-$it-setup" })
                outputDirectory.set(layout.buildDirectory.dir("compose/binaries/$outputName/exe"))
            }
            tasks.named("package${buildType}DistributionForCurrentOS") { dependsOn(installer) }
        }
    }
}

// Compose copies app resources without their executable bits. Repair the app image before installers consume it.
// Keep the installation read-only at runtime: changing a bundled executable after installation is unnecessary.
if (piTarget != null && piOs != "windows") {
    tasks.withType<AbstractJPackageTask>().configureEach {
        if (targetFormat == TargetFormat.AppImage) {
            val isMacBundle = piOs == "darwin"
            doLast {
                val imageTask = this as AbstractJPackageTask
                val resources = if (isMacBundle) {
                    "${imageTask.packageName.get()}.app/Contents/app/resources"
                } else {
                    "${imageTask.packageName.get()}/lib/app/resources"
                }
                val executable = imageTask.destinationDir.get().file("$resources/pi/pi").asFile
                check(executable.isFile && executable.setExecutable(true, false)) {
                    "Cannot mark packaged Pi executable: $executable"
                }
            }
        }
    }
}
