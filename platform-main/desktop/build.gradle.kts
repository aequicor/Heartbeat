import io.aequicor.heartbeat.buildlogic.CompressReleaseDmg
import io.aequicor.heartbeat.buildlogic.HarnessProbeArguments
import io.aequicor.heartbeat.buildlogic.PackageInnoSetup
import io.aequicor.heartbeat.buildlogic.PreparePiRuntime
import io.aequicor.heartbeat.buildlogic.PrepareReleaseRuntimeJars
import io.aequicor.heartbeat.buildlogic.VerifyChromiumPanelArtifact
import io.aequicor.heartbeat.buildlogic.WriteComponentMetadata
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractCheckNativeDistributionRuntime
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask
import org.jetbrains.compose.desktop.application.tasks.AbstractJvmToolOperationTask
import org.jetbrains.compose.desktop.application.tasks.AbstractProguardTask
import org.jetbrains.compose.reload.gradle.ComposeHotRun
import java.time.Duration

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.heartbeat.detekt)
    alias(libs.plugins.heartbeat.jcef)
}

dependencies {
    implementation(projects.platformMain.shared)
    // Desktop bundles are discovered at runtime (ServiceLoader), so they only need to be on the runtime class path.
    runtimeOnly(projects.bundles.shell)
    runtimeOnly(projects.bundles.web)
    runtimeOnly(projects.bundles.engines)
    runtimeOnly(projects.bundles.agentRuntime)
    runtimeOnly(projects.bundles.studio)
    implementation(projects.designSystem.tokens)
    implementation(projects.designSystem.theme)
    implementation(projects.designSystem.components)
    implementation(projects.designSystem.layouts)
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)
    implementation(libs.compose.uiToolingPreview)
    implementation(libs.jbr.api)
    implementation(libs.jna.platform)
    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(projects.platformMain.diBundle)
    testImplementation(projects.core.bundles.api)
    testImplementation(projects.core.profileFacade.api)
    testImplementation(projects.features.aiEngine.facade.api)
}

// The app runtime is independent of Gradle's daemon and shared modules' compilation toolchains.
val desktopRuntime = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(libs.versions.desktop.jdk.get().toInt()))
    vendor.set(JvmVendorSpec.JETBRAINS)
}
val desktopJavaHome = desktopRuntime.map { it.metadata.installationPath.asFile.absolutePath }
// gradle.properties holds the development default; the release workflow passes the version of the release tag.
// jpackage accepts only MAJOR.MINOR.PATCH with a major of at least 1, and installed builds compare these numbers.
val heartbeatApplicationVersion = providers.gradleProperty("heartbeat.version").get().also { version ->
    require(Regex("[1-9][0-9]*\\.[0-9]+\\.[0-9]+").matches(version)) {
        "heartbeat.version must be MAJOR.MINOR.PATCH with a major of at least 1: $version"
    }
}
// The testing flavor (-Pheartbeat.flavor=testing) is a release build for end-to-end checks: its launcher keeps
// credentials in the local development files, so a test run never asks for Keychain, DPAPI or Secret Service. Only its
// application name and macOS bundle id differ: it is not isolated from an installed Heartbeat and shares the data
// root, the single-instance lock, the logs and the Native Messaging registration with it, so it opens the real
// profiles (their secrets are not in the development files). Run it where no Heartbeat is installed or running.
// Release workflows never pass it. Its installers are refused below: the Linux package name (--linux-package-name) and
// the Windows Inno Setup AppId, install directory and installer file name stay those of Heartbeat, on macOS the
// refusal is conservative.
val heartbeatFlavor = providers.gradleProperty("heartbeat.flavor").orNull.also { flavor ->
    require(flavor == null || flavor == "testing") { "heartbeat.flavor must be testing: $flavor" }
}
val isTestingFlavor = heartbeatFlavor == "testing"
if (isTestingFlavor) {
    val rejectTestingInstallers = tasks.register("rejectTestingInstallers") {
        doFirst {
            throw GradleException(
                "The testing flavor builds createReleaseDistributable only: an installer (Dmg, Deb, Inno Setup) " +
                    "could reuse the production package name, AppId and file names and replace an installed Heartbeat",
            )
        }
    }
    val installerTask = Regex("package(Release)?(Dmg|Pkg|Msi|Exe|Deb|Rpm|InnoSetup)")
    // Compose registers its packaging tasks after evaluation; the callback also covers those.
    tasks.configureEach { if (installerTask.matches(name)) dependsOn(rejectTestingInstallers) }
}
// Installed release builds update themselves from this repository's GitHub releases (owner/name).
val heartbeatUpdateRepository = providers.gradleProperty("heartbeat.updateRepository").get().also { repository ->
    require(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(repository)) {
        "heartbeat.updateRepository must be owner/name: $repository"
    }
}

tasks.withType<Test>().configureEach {
    // Match launchHeartbeat's owned-window layers, including tests that do not enter main().
    systemProperties(heartbeatJcef.renderingProperties)
}

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

// Development uses the host archive; release staging retains bridge classes and defers Chromium installation.
heartbeatJcef.nativeBundle?.let { dependencies { runtimeOnly(it) } }
// Module roots that keep the pinned API/native pair ahead of JBR's own jcef module, see heartbeat.jcef.
val jcefJvmArgs = heartbeatJcef.jvmArguments
// K2 compiles scripts without javac or dynamic JVM attachment. Excluding these tools also omits ct.sym.
// The packaged harness probe exercises compilation and evaluation against this exact smaller runtime.
val packagedJdkModules = heartbeatJcef.jdkModules.filterNot { it == "jdk.compiler" || it == "jdk.attach" }
// Verify the pinned inputs before release staging removes the native archive.
val verifyBrowserRuntime = heartbeatJcef.registerRuntimeVerification("runtimeClasspath")
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

// First-use installation uses the same reviewed publisher pin as the optional development archive.
piTarget?.let { target ->
    val piAsset = "pi-$target." + if (piOs == "windows") "zip" else "tar.gz"
    val piMetadata = tasks.register<WriteProperties>("preparePiMetadata") {
        destinationFile.set(layout.buildDirectory.file("generated/piMetadata/runtime.properties"))
        property("version", libs.versions.pi.get())
        property("target", target)
        property("asset", piAsset)
        property("sha256", piChecksums.getValue(target))
        property("url", "https://github.com/earendil-works/pi/releases/download/v${libs.versions.pi.get()}/$piAsset")
    }
    tasks.named<ProcessResources>("processResources") {
        from(piMetadata) { into("pi") }
    }
}

// The optional extension is a separate release asset; only its pinned metadata enters release installers.
// Runs from a checkout use the repository's extension folder instead (DesktopExtensionFolder).
// CI passes the artifact exported by prepareChromiumPanelArtifact from this release's Linux build.
// Without the property, local packaging continues to build the panel from the checkout.
val prebuiltChromiumPanel = providers.gradleProperty("heartbeat.chromiumPanelDir").orNull?.let { path ->
    require(path.isNotBlank()) { "heartbeat.chromiumPanelDir must name a prebuilt Chromium panel directory" }
    layout.settingsDirectory.dir(path)
}
val verifyChromiumPanelArtifact = if (prebuiltChromiumPanel != null) {
    tasks.register<VerifyChromiumPanelArtifact>("verifyChromiumPanelArtifact") {
        group = "verification"
        description = "Checks the complete prebuilt Chromium panel before desktop packaging."
        panelDirectory.set(prebuiltChromiumPanel)
    }
} else {
    null
}
val collectExtensionResources = tasks.register<Sync>("collectExtensionResources") {
    if (verifyChromiumPanelArtifact != null) {
        dependsOn(verifyChromiumPanelArtifact)
        from(verifyChromiumPanelArtifact.flatMap { it.panelDirectory }) {
            into("app")
            exclude("SHA256SUMS")
        }
    } else {
        dependsOn(":chromium-plugin:panel:syncChromiumPanel")
    }
    from(layout.settingsDirectory.dir("chromium-plugin")) {
        // Tests, editor typings, a private signing key and the Kotlin sources of the side panel (built into app/)
        // are not part of the extension.
        exclude("tests/**", "jsconfig.json", "src/types/**", "*.pem", "panel/**", "**/*.map")
        if (prebuiltChromiumPanel != null) exclude("app/**")
    }
    exclude("**/*.map")
    into(layout.buildDirectory.dir("generated/chromiumExtension"))
}
// Every platform imports the same Wasm panel and produces the same ZIP, recorded in its installer metadata.
val packageChromiumExtension = tasks.register<Zip>("packageChromiumExtension") {
    group = "distribution"
    description = "Packages the optional Chromium extension for publication with the desktop release."
    from(collectExtensionResources)
    archiveFileName.set("Heartbeat-$heartbeatApplicationVersion-chromium-extension.zip")
    destinationDirectory.set(layout.buildDirectory.dir("release-components"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    // Only the Linux job's archive is published, yet every installer pins its own host's archive. The panel artifact
    // loses empty Compose resource directories in transit, so entries for them would change the bytes per host.
    includeEmptyDirs = false
}
val prepareExtensionMetadata = tasks.register<WriteComponentMetadata>("prepareExtensionMetadata") {
    artifact.set(packageChromiumExtension.flatMap { it.archiveFile })
    version.set(heartbeatApplicationVersion)
    downloadUrl.set(
        "https://github.com/$heartbeatUpdateRepository/releases/download/v$heartbeatApplicationVersion/" +
            "Heartbeat-$heartbeatApplicationVersion-chromium-extension.zip",
    )
    outputFile.set(layout.buildDirectory.file("generated/extensionMetadata/chromium-extension.properties"))
}
val collectAppResources = tasks.register<Sync>("collectAppResources") {
    from(prepareExtensionMetadata) { into("common") }
    from(layout.projectDirectory.file("pi/LICENSE")) { into("common/licenses/pi") }
    into(layout.buildDirectory.dir("generated/appResources"))
}
// Packaged development builds have no published release asset for their local version or archive bytes.
// Ship their extension alongside the launcher so DesktopExtensionFolder can prepare it without a download.
val collectDevelopmentAppResources = tasks.register<Sync>("collectDevelopmentAppResources") {
    from(collectAppResources.map { it.destinationDir.resolve("common") })
    from(collectExtensionResources) { into("chromium-plugin") }
    into(layout.buildDirectory.dir("generated/developmentAppResources"))
}

compose.desktop {
    application {
        mainClass = if (isTestingFlavor) {
            "io.aequicor.heartbeat.platform.desktop.TestingMainKt"
        } else {
            "io.aequicor.heartbeat.platform.desktop.MainKt"
        }
        jvmArgs += jcefJvmArgs
        jvmArgs("-Dheartbeat.app.version=$heartbeatApplicationVersion")
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
            modules(*packagedJdkModules.toTypedArray())
            modules(
                "java.instrument",
                "java.compiler",
                "java.management",
                "java.prefs",
                "java.scripting",
                "jdk.httpserver",
                "jdk.unsupported",
            )
            // The installed app's name: Heartbeat.app on macOS, Heartbeat.exe on Windows (heartbeat.iss cleans up the
            // launcher of installations made before the rename).
            packageName = if (isTestingFlavor) "HeartbeatE2E" else "Heartbeat"
            packageVersion = heartbeatApplicationVersion
            description = "Heartbeat AI Studio"
            vendor = "Aequicor"
            macOS {
                iconFile.set(layout.projectDirectory.file("icons/heartbeat.icns"))
                dockName = if (isTestingFlavor) "Heartbeat E2E" else "Heartbeat"
                if (isTestingFlavor) bundleID = "io.aequicor.heartbeat.platform.desktop.testing"
            }
            windows {
                iconFile.set(layout.projectDirectory.file("icons/heartbeat.ico"))
            }
            linux {
                // Debian package names are lowercase.
                packageName = "heartbeat"
                shortcut = true
                iconFile.set(layout.projectDirectory.file("icons/heartbeat.png"))
            }
            appResourcesRootDir.set(layout.dir(collectAppResources.map { it.destinationDir }))
        }
    }
}

// Compose bundles Hot Reload; keep its launchers on the same development-only entry point as run.
tasks.withType<ComposeHotRun>().configureEach {
    mainClass.set("io.aequicor.heartbeat.platform.desktop.DevelopmentMainKt")
    jvmArgs.addAll(jcefJvmArgs)
    // Hot Reload does not pass Compose app resources; attach the bundled Pi runtime the same way run does.
    preparePiRuntime?.let { task ->
        dependsOn(task)
        // A configuration-time path: the Hot Reload argfile task resolves JVM arguments before tasks run.
        val resourcesDir = piResourcesDir.get().dir("common").asFile.absolutePath
        systemProperty("compose.application.resources.dir", resourcesDir)
    }
}

// The default Compose build is development; release tasks use the protected MainKt entry point (TestingMainKt in the
// testing flavor).
// Configure after Compose has registered and initialized its tasks, without an environment/property escape hatch.
afterEvaluate {
    val releaseJars = tasks.named<AbstractProguardTask>("proguardReleaseJars")
    val prepareReleaseRuntimeJars = tasks.register<PrepareReleaseRuntimeJars>("prepareReleaseRuntimeJars") {
        group = "compose desktop"
        description = "Removes native libraries for other hosts from release jars before packaging."
        inputDirectory.set(releaseJars.flatMap { it.destinationDir })
        outputDirectory.set(layout.buildDirectory.dir("compose/tmp/host-release-jars"))
        targetOs.set(if (piOs == "darwin") "macos" else piOs.orEmpty())
        targetArch.set(piArch.orEmpty())
        deferredJcefPlatform.set(heartbeatJcef.hostPlatform.orEmpty())
        deferredJarPrefixes.set(
            listOf(
                "kotlin-compiler-embeddable",
                "kotlin-scripting-compiler-embeddable",
                "kotlin-scripting-compiler-impl-embeddable",
                "kotlin-daemon-embeddable",
                "kotlin-build-tools-api",
                "kotlin-scripting-jvm-host",
            ).map { "$it-${libs.versions.kotlin.get()}-" } + "plantuml-mit-${libs.versions.plantuml.get()}-",
        )
    }
    val releaseImage = tasks.named<AbstractJPackageTask>("createReleaseDistributable")
    tasks.register<Exec>("verifyReleaseHarnessCompiler") {
        group = "verification"
        description = "Compiles, evaluates and reloads harness scripts in the packaged release runtime."
        dependsOn(releaseImage)
        val launcher = releaseImage.flatMap { image ->
            image.destinationDir.file(image.packageName.map { name ->
                when (piOs) {
                    "darwin" -> "$name.app/Contents/MacOS/$name"
                    "windows" -> "$name/$name.exe"
                    else -> "$name/bin/$name"
                }
            })
        }
        executable(launcher.get().asFile)
        argumentProviders.add(objects.newInstance<HarnessProbeArguments>().apply {
            fileNames.set(
                listOf(
                    "kotlin-compiler-embeddable",
                    "kotlin-scripting-compiler-embeddable",
                    "kotlin-scripting-compiler-impl-embeddable",
                    "kotlin-daemon-embeddable",
                    "kotlin-build-tools-api",
                    "kotlin-scripting-jvm-host",
                ).map { "$it-${libs.versions.kotlin.get()}.jar" },
            )
            compilerFiles.from(configurations.runtimeClasspath)
        })
        timeout.set(Duration.ofMinutes(5))
    }
    tasks.withType<AbstractJvmToolOperationTask>().configureEach {
        javaHome.set(desktopJavaHome)
    }
    tasks.withType<AbstractCheckNativeDistributionRuntime>().configureEach {
        jdkHome.set(desktopJavaHome)
    }
    tasks.withType<AbstractJPackageTask>().configureEach {
        if (targetFormat == TargetFormat.Dmg) {
            // Compose omits --icon when packaging from an existing --app-image, as it does for a DMG; jpackage then
            // takes the volume icon (.VolumeIcon.icns, the Finder window title and path bar) from its Java default.
            // Compose's argfile keeps free arguments verbatim, so the path is quoted like its own paths.
            freeArgs.addAll(iconFile.map { icon -> listOf("--icon", "\"${icon.asFile.absolutePath}\"") })
            if (name.contains("Release")) {
                doLast(objects.newInstance<CompressReleaseDmg>().apply { imageDirectory.set(destinationDir) })
            }
        }
        launcherJvmArgs.set(launcherJvmArgs.get().map { argument ->
            if (argument.startsWith("--limit-modules=")) {
                "--limit-modules=${packagedJdkModules.joinToString(",")}"
            } else {
                argument
            }
        })
        dependsOn(verifyWindowRuntime)
        dependsOn(verifyBrowserRuntime)
        if (targetFormat == TargetFormat.Deb) {
            // jpackage cannot discover libraries loaded through JNA or from the JCEF native archive.
            // Compose exposes extra jpackage options on the task, not in LinuxPlatformSettings.
            // These package names target Ubuntu 24.04; no spaces keeps the value intact in Compose's argfile.
            freeArgs.addAll(
                "--linux-package-deps",
                listOf(
                    "libsecret-1-0",
                    "libnss3",
                    "libnspr4",
                    "libatk1.0-0t64",
                    "libatk-bridge2.0-0t64",
                    "libcups2t64",
                    "libdrm2",
                    "libxkbcommon0",
                    "libxcomposite1",
                    "libxdamage1",
                    "libxfixes3",
                    "libxrandr2",
                    "libgbm1",
                    "libasound2t64",
                    "libgtk-3-0t64",
                    "libx11-6",
                    "libxcb1",
                    "libxext6",
                    "libxrender1",
                    "libxtst6",
                ).joinToString(","),
            )
        }
        if (!name.contains("Release")) {
            launcherMainClass.set("io.aequicor.heartbeat.platform.desktop.DevelopmentMainKt")
            dependsOn(collectDevelopmentAppResources)
            appResourcesDir.set(layout.dir(collectDevelopmentAppResources.map { it.destinationDir }))
            // Compose marks appResourcesDir @Internal; changed extension files must invalidate packaging.
            inputs.dir(appResourcesDir)
                .withPropertyName("developmentAppResources")
                .withPathSensitivity(PathSensitivity.RELATIVE)
        } else {
            // Stage the ProGuard output separately: development and verified upstream JCEF jars stay intact.
            // Compose still extracts and signs the host's Skiko dylib from the filtered jar on macOS.
            dependsOn(prepareReleaseRuntimeJars)
            files.setFrom(fileTree(prepareReleaseRuntimeJars.flatMap { it.outputDirectory }))
            launcherMainJar.set(prepareReleaseRuntimeJars.flatMap { prepared ->
                releaseJars.flatMap { original -> prepared.outputDirectory.file(original.mainJar.get().asFile.name) }
            })
            // Only the release launcher names the update source: development launches never replace themselves,
            // and the testing flavor must not swap itself for a published release.
            if (!isTestingFlavor) launcherJvmArgs.add("-Dheartbeat.app.update.repository=$heartbeatUpdateRepository")
        }
    }
    tasks.withType<JavaExec>().configureEach {
        javaLauncher.set(desktopRuntime)
        jvmArgs(jcefJvmArgs)
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
