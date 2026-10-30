import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.heartbeat.detekt)
}

// Иконка окна встраивается в exe как base64-константа: лончер не зависит от рабочего каталога
// и показывает иконку даже при запуске вне каталога установки (E2E, dev-прогоны).
val embeddedIconFile = rootProject.file("platform-main/desktop/icons/heartbeat.ico")
val generateEmbeddedIcon = tasks.register("generateEmbeddedIcon") {
    val iconFile = embeddedIconFile
    val outDir = layout.buildDirectory.dir("generated-src/icon")
    inputs.file(iconFile)
    outputs.dir(outDir)
    doLast {
        val dir = outDir.get().asFile.apply { mkdirs() }
        val encoded = Base64.getEncoder().encodeToString(iconFile.readBytes())
        dir.resolve("EmbeddedIcon.kt").writeText(
            """
            package io.aequicor.heartbeat.platform.nativelauncher

            /** Base64 от platform-main/desktop/icons/heartbeat.ico; генерируется задачей generateEmbeddedIcon. */
            internal const val EMBEDDED_ICON_BASE64: String = "$encoded"
            """.trimIndent() + "\n",
        )
    }
}

val generateBootstrapDefaults = tasks.register("generateBootstrapDefaults") {
    val keys = layout.projectDirectory.file("bootstrap-trusted-keys.txt")
    val version = providers.gradleProperty("heartbeat.version")
    val channel = providers.gradleProperty("heartbeat.channel").orElse("release")
    val output = layout.buildDirectory.dir("generated-src/bootstrap")
    inputs.file(keys)
    inputs.property("version", version)
    inputs.property("channel", channel)
    outputs.dir(output)
    doLast {
        val trusted = keys.asFile.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        require(trusted.isNotEmpty()) { "Bootstrap release keys are missing" }
        trusted.forEach { key ->
            KeyFactory.getInstance("Ed25519").generatePublic(
                X509EncodedKeySpec(Base64.getDecoder().decode(key)),
            )
        }
        require(version.get().matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) { "Invalid bootstrap version" }
        require(channel.get() in listOf("release", "beta", "dev")) { "Invalid bootstrap channel" }
        val dir = output.get().asFile.apply { mkdirs() }
        dir.resolve("BootstrapDefaults.kt").writeText(
            "package io.aequicor.heartbeat.platform.nativelauncher\n\n" +
                "internal const val BOOTSTRAP_VERSION = \"${version.get()}\"\n" +
                "internal const val BOOTSTRAP_CHANNEL = \"${channel.get()}\"\n" +
                "internal val BOOTSTRAP_PUBLIC_KEYS = listOf(" +
                trusted.joinToString(", ") { "\"$it\"" } + ")\n",
        )
    }
}

// Только desktop-хосты нативного лончера: общий код bootstrap-протокола, JVM — для тестов и инструментов.
// Unix-таргеты сначала проверяют общий bootstrap-код; исполняемые входы подключаются отдельно.
// Это не библиотека «для всех платформ Heartbeat», поэтому heartbeat.kmp.library (android/iOS) не применяется.
kotlin {
    jvmToolchain(17)
    jvm()
    listOf(macosX64(), macosArm64()).forEach { target ->
        val arch = if (target.name == "macosArm64") "arm64" else "x86_64"
        val bridgeSource = layout.projectDirectory.file("src/nativeInterop/cinterop/macos_ui.m")
        val bridgeHeader = layout.projectDirectory.file("src/nativeInterop/cinterop/macos_ui.h")
        val bridgeObject = layout.buildDirectory.file("native-bridge/$arch/macos_ui.o")
        val macHost = providers.systemProperty("os.name").map { it.startsWith("Mac") }
        val compileBridge = tasks.register<Exec>("compileMacosUi" + target.name.replaceFirstChar { it.uppercase() }) {
            inputs.files(bridgeSource, bridgeHeader)
            inputs.property("arch", arch)
            outputs.file(bridgeObject)
            if (macHost.get()) {
                inputs.property("sdk", providers.exec {
                    commandLine("/usr/bin/xcrun", "--sdk", "macosx", "--show-sdk-path")
                }.standardOutput.asText)
                inputs.property("clang", providers.exec {
                    commandLine("/usr/bin/xcrun", "--sdk", "macosx", "clang", "--version")
                }.standardOutput.asText)
            }
            onlyIf { macHost.get() }
            doFirst { bridgeObject.get().asFile.parentFile.mkdirs() }
            commandLine("/usr/bin/xcrun", "--sdk", "macosx", "clang", "-fobjc-arc", "-fblocks",
                "-arch", arch, "-mmacosx-version-min=12.0", "-c", bridgeSource.asFile.absolutePath,
                "-o", bridgeObject.get().asFile.absolutePath)
        }
        target.compilations.getByName("main").cinterops.create("macosUi") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/macosUi.def"))
            includeDirs(project.file("src/nativeInterop/cinterop"))
        }
        target.binaries.all {
            linkerOpts(bridgeObject.get().asFile.absolutePath)
            linkTaskProvider.configure {
                dependsOn(compileBridge)
                inputs.file(bridgeObject)
            }
        }
        target.binaries.executable {
            baseName = "heartbeat-launcher"
            entryPoint = "io.aequicor.heartbeat.platform.nativelauncher.main"
        }
    }
    linuxX64 {
        compilations.getByName("main").cinterops.create("linuxUi") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/linuxUi.def"))
            includeDirs(project.file("src/nativeInterop/cinterop"))
        }
        binaries.executable {
            baseName = "heartbeat-launcher"
            entryPoint = "io.aequicor.heartbeat.platform.nativelauncher.main"
        }
    }
    mingwX64 {
        compilations.getByName("main").cinterops.create("windowsProcess") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/windowsProcess.def"))
        }
        binaries {
            executable {
                baseName = "heartbeat-launcher"
                entryPoint = "io.aequicor.heartbeat.platform.nativelauncher.main"
                // GUI-подсистема: консольного окна-«чёрного квадрата» за спиной окна нет.
                linkerOpts("-mwindows")
            }
        }
    }

    applyHierarchyTemplate {
        common {
            withJvm()
            group("native") {
                group("mingw") { withMingw() }
                group("unix") {
                    group("macos") { withMacos() }
                    group("linux") { withLinux() }
                }
            }
        }
    }

    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
        if (konanTarget.family.isAppleFamily || konanTarget.family == org.jetbrains.kotlin.konan.target.Family.LINUX) {
            compilations.getByName("main").cinterops.create("provisioning") {
                definitionFile.set(project.file("src/nativeInterop/cinterop/provisioning.def"))
            }
        }
    }

    sourceSets {
        nativeMain {
            kotlin.srcDir(generateBootstrapDefaults.map { it.outputs.files.single() })
        }
        macosMain.dependencies { implementation(libs.ktor.client.darwin) }
        linuxMain.dependencies { implementation(libs.ktor.client.curl) }
        nativeMain.dependencies {
            implementation(libs.ktor.client.core)
            implementation(libs.ionspin.libsodium)
        }
        mingwMain {
            kotlin.srcDir(generateEmbeddedIcon.map { it.outputs.files.single() })
        }
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.okio)
        }
        nativeTest.dependencies { implementation(libs.ktor.client.mock) }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmMain.dependencies {
            implementation(projects.core.logging)
        }
        mingwMain.dependencies {
            implementation(libs.ktor.client.winhttp)
        }
    }
}


// The headless Linux ABI smoke uses the existing module-build runner without changing its workflow.
// Other native tests need no display; this task deliberately supplies one for the GTK-only test.
tasks.register<Exec>("linuxGtkSmokeTest") {
    dependsOn("linkDebugTestLinuxX64")
    val binary = layout.buildDirectory.file("bin/linuxX64/debugTest/test.kexe")
    inputs.file(binary)
    val linuxHost = providers.systemProperty("os.name").map { it.startsWith("Linux") }
    onlyIf { linuxHost.get() }
    commandLine("xvfb-run", "--auto-servernum", binary.get().asFile.absolutePath, "--ktest_filter=*LinuxUiTest*")
}

// Native failures otherwise appear only as AssertionError at null:-1, hiding the actual violated assertion.
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach {
    testLogging.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
}
