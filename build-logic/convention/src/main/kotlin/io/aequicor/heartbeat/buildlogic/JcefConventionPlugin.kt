package io.aequicor.heartbeat.buildlogic

import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.jvm.toolchain.JvmVendorSpec
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register

/**
 * `heartbeat.jcef` — the pinned JCEF runtime shared by `:features:browser:impl`, `:platform-main:desktop` and
 * native tests. Adds the `heartbeatJcef` extension ([JcefRuntimeExtension]); modules decide what to wire.
 */
class JcefConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.extensions.create("heartbeatJcef", JcefRuntimeExtension::class.java, target)
    }
}

/**
 * One source for the host's JCEF runtime: the native jar from the version catalog, JDK module roots and JVM
 * arguments for the JetBrains Runtime, the desktop host's Compose rendering properties, runtime verification
 * over the classpath a module actually runs with, and opt-in native test tasks in isolated JVMs.
 */
open class JcefRuntimeExtension(private val project: Project) {
    private val libs = project.extensions.getByType<VersionCatalogsExtension>().named("libs")
    private val osName = project.providers.systemProperty("os.name").get()
    private val architecture = project.providers.systemProperty("os.arch").get()
    private val isArm = architecture.lowercase() in ARM_ARCHITECTURES
    private val isMac = osName.startsWith("Mac")
    private val isWindows = osName.startsWith("Windows")

    /** jcefmaven platform of this host (`macosx-arm64`, `windows-amd64`…), `null` where no runtime is published. */
    val hostPlatform: String? = jcefNativePlatform(osName, architecture)

    /** The checksum-pinned native jar for [hostPlatform]; the app never downloads a runtime at launch. */
    val nativeBundle: Provider<MinimalExternalModuleDependency>? =
        hostPlatform?.let { platform -> libs.findLibrary(NATIVE_ALIASES.getValue(platform)).get() }

    /**
     * JBR may bundle an older `jcef` module. Limiting the root modules to these keeps the pinned classpath
     * API/native pair authoritative in development, tests and the jlink runtime of packaged distributions.
     */
    val jdkModules: List<String> = listOf(
        "java.se", "jdk.unsupported", "jdk.httpserver", "jdk.crypto.ec", "jdk.zipfs", "jdk.attach", "jdk.compiler",
    )

    /** JVM arguments of every process embedding JCEF: [jdkModules] roots and the AWT internals of macOS. */
    val jvmArguments: List<String> = listOf("--limit-modules=${jdkModules.joinToString(",")}") + if (isMac) {
        listOf(
            "--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
        )
    } else {
        emptyList()
    }

    /**
     * What `configureDesktopRendering` (`platform-main/desktop/.../DesktopRendering.kt`) sets before Compose starts:
     * owned WINDOW layers for popups, macOS interop blending so rounded clips retain their real backdrop,
     * and on Windows a renderer that does not reenter Direct3D reshape (software on ARM64, where Skiko has no OpenGL).
     * Keep both in sync.
     */
    val renderingProperties: Map<String, String> = buildMap {
        put("compose.layers.type", "WINDOW")
        if (isMac) put("compose.interop.blending", "true")
        if (isWindows) put("skiko.renderApi", if (isArm) "SOFTWARE_FAST" else "OPENGL")
    }

    /** The JetBrains Runtime of the desktop app (`desktop-jdk` in the catalog). */
    val runtimeLauncher: Provider<JavaLauncher> =
        project.extensions.getByType<JavaToolchainService>().launcherFor {
            languageVersion.set(JavaLanguageVersion.of(libs.findVersion("desktop-jdk").get().requiredVersion.toInt()))
            vendor.set(JvmVendorSpec.JETBRAINS)
        }

    private var verification: TaskProvider<VerifyBrowserRuntime>? = null

    /**
     * Registers `verifyBrowserRuntime`: the JCEF jars resolved by [configurations] must be exactly the locked API and
     * the host native pair of `native/jcef/source-lock.json`. Skipped on hosts without a published runtime.
     */
    fun registerRuntimeVerification(vararg configurations: String): TaskProvider<VerifyBrowserRuntime> {
        check(verification == null) { "verifyBrowserRuntime is already registered in ${project.path}" }
        val names = configurations.toList()
        val group = libs.findLibrary("jcef-api").get().get().module.group
        val root = project.rootProject.layout.projectDirectory
        return project.tasks.register<VerifyBrowserRuntime>("verifyBrowserRuntime") {
            this.group = "verification"
            description = "Checks the locked JCEF API and host native jars on this module's runtime classpath."
            version.set(libs.findVersion("jcef-native").get().requiredVersion)
            platform.set(hostPlatform.orEmpty())
            manifest.set(root.file("native/jcef/source-lock.json"))
            patch.set(root.file("native/jcef/request-context-settings.patch"))
            names.forEach { name ->
                artifacts.from(
                    project.configurations.named(name).map { configuration ->
                        configuration.incoming.artifactView {
                            componentFilter { it is ModuleComponentIdentifier && it.group == group }
                        }.files
                    },
                )
            }
            marker.set(project.layout.buildDirectory.file("jcef/verified-runtime.txt"))
            onlyIf { (it as VerifyBrowserRuntime).platform.get().isNotEmpty() }
        }.also { verification = it }
    }

    /** Runs [test] like the desktop app: JBR, [jvmArguments], [renderingProperties], after runtime verification. */
    fun configureNativeRuntime(test: Test) {
        test.javaLauncher.set(runtimeLauncher)
        test.jvmArgs(jvmArguments)
        test.systemProperties(renderingProperties)
        verification?.let { test.dependsOn(it) }
    }

    /**
     * Registers an opt-in native test task: [testClass] of `jvmTest` with [enablingProperty] set to `true`, one
     * fresh JVM per class (a CefApp cannot restart in one process), never up to date and not part of `check`.
     * All such tasks share exclusive desktop access across projects; compilation can still run in parallel.
     * Requires an interactive desktop session; the regular `jvmTest` skips the class through its assumption.
     */
    fun registerNativeTest(
        name: String,
        description: String,
        testClass: String,
        enablingProperty: String,
        configure: Action<Test> = Action { },
    ): TaskProvider<Test> {
        val regular = project.tasks.named<Test>("jvmTest")
        val desktop = project.gradle.sharedServices.registerIfAbsent(
            "heartbeatNativeDesktopTests",
            NativeDesktopTestService::class.java,
        ) {
            maxParallelUsages.set(1)
        }
        return project.tasks.register<Test>(name) {
            group = "verification"
            this.description = description
            testClassesDirs = regular.get().testClassesDirs
            classpath = regular.get().classpath
            filter { includeTestsMatching("*.$testClass") }
            maxParallelForks = 1
            forkEvery = 1
            usesService(desktop)
            outputs.upToDateWhen { false }
            outputs.doNotCacheIf("Native checks must execute in a fresh worker") { true }
            systemProperty(enablingProperty, "true")
            systemProperty("java.awt.headless", "false")
            configureNativeRuntime(this)
            configure.execute(this)
        }
    }

    private companion object {
        val ARM_ARCHITECTURES = setOf("arm64", "aarch64")
        val NATIVE_ALIASES = mapOf(
            "macosx-arm64" to "jcef-macos-arm64",
            "macosx-amd64" to "jcef-macos-x64",
            "windows-arm64" to "jcef-windows-arm64",
            "windows-amd64" to "jcef-windows-x64",
            "linux-amd64" to "jcef-linux-x64",
        )
    }
}

/** The Linux release currently ships only x64 JNI; unsupported hosts must not load another architecture. */
internal fun jcefNativePlatform(osName: String, architecture: String): String? {
    val arch = when (architecture.lowercase()) {
        "aarch64", "arm64" -> "arm64"
        "amd64", "x86_64" -> "amd64"
        else -> return null
    }
    return when {
        osName.startsWith("Mac") -> "macosx-$arch"
        osName.startsWith("Windows") -> "windows-$arch"
        osName.startsWith("Linux") && arch == "amd64" -> "linux-amd64"
        else -> null
    }
}
