import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

plugins {
    alias(libs.plugins.heartbeat.kmp.library)
    alias(libs.plugins.heartbeat.metro)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.heartbeat.jcef)
    // Only for jvmTest: the host Skiko runtime (`compose.desktop.currentOs`) of the native recovery window.
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// The production graph has no composables: the Compose compiler runs for the jvmTest compilation alone.
composeCompiler { targetKotlinPlatforms.set(emptySet<KotlinPlatformType>()) }
dependencies { "kotlinCompilerPluginClasspathJvmTest"(libs.kotlin.composeCompilerPlugin) }

// Единственный модуль, который видит все impl: здесь Metro собирает контрибуции и генерирует граф.
// Граф объявляется per-platform (androidMain/jvmMain/iosMain), чтобы видеть платформенные контрибуции.
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.features.aiEngine.koog.api)
            implementation(projects.features.aiEngine.koog.impl)
            api(projects.features.aiEngine.claude.api)
            implementation(projects.features.aiEngine.claude.impl)
            api(projects.core.common)
            api(projects.features.aiEngine.facade.api)
            implementation(projects.features.aiEngine.facade.impl)
            api(projects.features.aiEngine.pi.api)
            implementation(projects.features.aiEngine.pi.impl)
            implementation(projects.features.aiEngine.acpInterface.impl)
            implementation(projects.features.aiEngine.codex.impl)
            api(projects.features.welcome.api)
            implementation(projects.features.welcome.impl)
            api(projects.features.questionnaire.api)
            implementation(projects.features.questionnaire.impl)
            implementation(projects.features.checklist.impl)
            implementation(projects.features.aiStudio.impl)
            implementation(projects.features.aiStudio.api)
            implementation(projects.features.searchEngine.impl)
            implementation(projects.features.togglesPanel.impl)
            implementation(projects.features.settings.impl)
            implementation(projects.features.appLogs.impl)
            implementation(projects.features.aiEngine.authenticator.impl)
            implementation(projects.features.aiEngine.facade.impl)
            implementation(projects.features.aiEngine.connections.impl)
            implementation(projects.features.aiEngine.controlPanel.impl)
            implementation(projects.features.aiSessionEngineTransfer.impl)
            implementation(projects.features.effortConfiguration.impl)
            implementation(projects.features.contextWindowOptimization.impl)
            implementation(projects.features.worktreeMode.impl)
            implementation(projects.features.computerUse.impl)
            api(projects.features.computerUse.api)
            implementation(projects.features.feedback.api)
            implementation(projects.features.feedback.impl)
            implementation(projects.features.attachments.api)
            implementation(projects.features.attachments.impl)
            implementation(projects.features.harness.api)
            implementation(projects.features.harness.impl)
            implementation(projects.features.agentLearning.api)
            implementation(projects.features.agentLearning.impl)
            implementation(projects.features.adaptiveSubagents.api)
            implementation(projects.features.adaptiveSubagents.impl)
            implementation(projects.features.autocomplete.api)
            implementation(projects.features.autocomplete.impl)
            implementation(projects.features.organicAi.api)
            implementation(projects.features.organicAi.impl)
            api(projects.features.plantUmlSupport.api)
            implementation(projects.features.scheduler.api)
            implementation(projects.features.scheduler.impl)
            api(projects.features.appUpdate.api)
            implementation(projects.features.appUpdate.impl)
            implementation(projects.features.serviceAsUi.api)
            implementation(projects.features.serviceAsUi.impl)
            implementation(projects.features.projectConfig.api)
            implementation(projects.features.projectConfig.impl)
            implementation(projects.features.planningMode.api)
            implementation(projects.features.planningMode.impl)
            api(projects.core.di.api)
            api(projects.core.profileFacade.api)
            api(projects.core.navigation.api)
            api(projects.core.datastore.api)
            api(projects.core.secrets.api)
            implementation(projects.core.secrets.impl)
            implementation(projects.core.desktopDialogs)
            api(projects.core.stateMachine.api)
            implementation(projects.core.logging)
            implementation(projects.core.mvi)
            implementation(projects.core.network.api)
            implementation(projects.core.featureToggles.api)
            implementation(projects.core.di.impl)
            implementation(projects.core.profileFacade.impl)
            implementation(projects.core.navigation.impl)
            implementation(projects.core.network.impl)
            implementation(projects.core.datastore.impl)
            implementation(projects.core.stateMachine.impl)
            implementation(projects.core.featureToggles.impl)
        }
        // Mobile hosts compile the browser, the Chromium bridge and the research chat into the static graph; on
        // desktop they live in the `web` bundle.
        androidMain.dependencies {
            implementation(projects.features.browser.impl)
            implementation(projects.features.chromiumBridge.api)
            implementation(projects.features.chromiumBridge.impl)
            implementation(projects.features.researchChat.impl)
        }
        iosMain.dependencies {
            implementation(projects.features.browser.impl)
            implementation(projects.features.chromiumBridge.api)
            implementation(projects.features.chromiumBridge.impl)
            implementation(projects.features.researchChat.impl)
        }
        // Desktop bundles: discovered at runtime through ServiceLoader; the assemblers (:bundles:*) come with the desktop app.
        jvmMain.dependencies {
            implementation(projects.core.bundles.impl)
            implementation(projects.core.bundles.api)
            implementation(projects.features.browser.api)
        }
        jvmTest.dependencies {
            runtimeOnly(projects.bundles.web)
            implementation(projects.features.chromiumBridge.api)
            implementation(projects.features.aiStudio.screenModel)
            implementation(projects.core.navigation.compose)
            implementation(projects.designSystem.theme)
            implementation(libs.kotlinx.coroutinesSwing)
            implementation(compose.desktop.currentOs)
            implementation(projects.features.checklist.api)
            implementation(projects.features.researchChat.api)
            implementation(projects.features.browser.api)
            implementation(projects.features.aiEngine.acpInterface.api)
            implementation(projects.features.aiEngine.codex.api)
            implementation(projects.features.togglesPanel.api)
            implementation(projects.features.settings.api)
            implementation(projects.features.appLogs.api)
            implementation(projects.features.aiEngine.connections.api)
            implementation(projects.features.aiEngine.controlPanel.api)
            implementation(projects.features.aiSessionEngineTransfer.api)
            implementation(projects.features.effortConfiguration.api)
            implementation(projects.features.contextWindowOptimization.api)
            implementation(projects.features.agentLearning.api)
            implementation(projects.features.adaptiveSubagents.api)
            implementation(projects.features.scheduler.api)
            implementation(projects.features.organicAi.api)
            implementation(projects.features.planningMode.api)
            implementation(projects.core.di.ext)
            implementation(projects.features.aiEngine.authenticator.api)
            implementation(projects.features.aiEngine.facade.api)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

// The native research integration uses one CefApp in a dedicated test worker, never the general DI worker.
val recoveryNativeRuntime by configurations.creating {
    isCanBeConsumed = false
}
heartbeatJcef.nativeBundle?.let { dependencies { recoveryNativeRuntime(it) } }
heartbeatJcef.registerRuntimeVerification("jvmTestRuntimeClasspath", recoveryNativeRuntime.name)

val recoveryNativeRoot = layout.buildDirectory.dir("native-research-profile")
val prepareRecoveryNativeProfile = tasks.register<Delete>("prepareBrowserRecoveryNativeProfile") {
    delete(recoveryNativeRoot)
}
val cleanRecoveryNativeProfile = tasks.register<Delete>("cleanBrowserRecoveryNativeProfile") {
    delete(recoveryNativeRoot)
}
heartbeatJcef.registerNativeTest(
    name = "browserRecoveryNativeTest",
    description = "Checks actual Chromium recovery into research and attachment storage in an isolated JVM.",
    testClass = "ResearchBrowserNativeIntegrationTest",
    enablingProperty = "heartbeat.browser.recoveryNativeTest",
) {
    dependsOn(prepareRecoveryNativeProfile)
    finalizedBy(cleanRecoveryNativeProfile)
    classpath += recoveryNativeRuntime
    systemProperty("heartbeat.browser.recoveryNativeRoot", recoveryNativeRoot.get().asFile.absolutePath)
}

// Real Claude Code CLI through the production graph, opt-in and outside `check`: it needs an authenticated CLI
// (the Claude Code desktop app's is found automatically) and spends model quota.
val regularJvmTest = tasks.named<Test>("jvmTest")
tasks.register<Test>("actionSafetyLiveTest") {
    group = "verification"
    description = "Checks structured permissions with a real isolated Codex reviewer and a disposable project."
    testClassesDirs = regularJvmTest.get().testClassesDirs
    classpath = regularJvmTest.get().classpath
    filter { includeTestsMatching("*.ActionSafetyLiveE2eTest") }
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("Live checks must reach the real CLI") { true }
    systemProperty("heartbeat.actionSafety.liveTest", "true")
    testLogging { events("passed", "failed", "skipped") }
}

tasks.register<Test>("claudeLiveGraphTest") {
    group = "verification"
    description = "Runs the Claude engine through the production graph against the real Claude Code CLI."
    testClassesDirs = regularJvmTest.get().testClassesDirs
    classpath = regularJvmTest.get().classpath
    filter { includeTestsMatching("*.ClaudeLive*E2eTest") }
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("Live checks must reach the real CLI") { true }
    systemProperty("heartbeat.claude.liveTest", "true")
    systemProperty("heartbeat.claude.liveReport", layout.buildDirectory.file("claude-live-report.txt").get().asFile.path)
    testLogging { events("passed", "failed", "skipped") }
}

// Reuse the desktop publisher pin so the test graph can prepare the actual bundled Pi runtime.
val engineSwitchLiveResources = tasks.register<Sync>("prepareEngineSwitchLiveResources") {
    dependsOn(":platform-main:desktop:preparePiMetadata")
    from(project(":platform-main:desktop").layout.buildDirectory.dir("generated/piMetadata")) { into("pi") }
    into(layout.buildDirectory.dir("engine-switch-live-resources"))
}

// Pi's legacy-directory cleanup uses JVM user.home independently of the profile's StorageRoot.
val engineSwitchLiveHome = layout.buildDirectory.dir("engine-switch-live-home")
val prepareEngineSwitchLiveHome = tasks.register<Delete>("prepareEngineSwitchLiveHome") {
    delete(engineSwitchLiveHome)
}
val cleanEngineSwitchLiveHome = tasks.register<Delete>("cleanEngineSwitchLiveHome") {
    delete(engineSwitchLiveHome)
}

// Opt-in real Koog/Pi requests: credentials come from an explicitly supplied file, in an isolated test profile.
tasks.register<Test>("engineSwitchLiveTest") {
    group = "verification"
    description = "Checks studio engine switching against an explicitly configured compatible endpoint."
    testClassesDirs = regularJvmTest.get().testClassesDirs
    classpath = regularJvmTest.get().classpath
    classpath += files(engineSwitchLiveResources)
    filter { includeTestsMatching("*.EngineSwitchLiveE2eTest") }
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("Live checks must reach the configured provider") { true }
    dependsOn(prepareEngineSwitchLiveHome)
    finalizedBy(cleanEngineSwitchLiveHome)
    systemProperty("user.home", engineSwitchLiveHome.get().asFile.absolutePath)
    systemProperty("heartbeat.engineSwitch.liveHome", engineSwitchLiveHome.get().asFile.absolutePath)
    systemProperty("heartbeat.engineSwitch.liveTest", "true")
    testLogging { events("passed", "failed", "skipped") }
}

// Isolated, opt-in actual shell/approval regressions; never added to check or CI.
val securityLiveHome = layout.buildDirectory.dir("security-live-home")
val securityLiveReport = layout.buildDirectory.file("security-live-report.txt")
val prepareSecurityLiveHome = tasks.register<Delete>("prepareSecurityLiveHome") {
    delete(securityLiveHome, securityLiveReport)
}
val cleanSecurityLiveHome = tasks.register<Delete>("cleanSecurityLiveHome") {
    delete(securityLiveHome)
}
tasks.register<Test>("securityLiveTest") {
    group = "verification"
    description = "Checks profile permissions through real Koog/Pi tools against an explicitly configured provider."
    testClassesDirs = regularJvmTest.get().testClassesDirs
    classpath = regularJvmTest.get().classpath + files(engineSwitchLiveResources)
    filter { includeTestsMatching("*.SecurityLiveE2eTest") }
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("Live checks must reach the configured provider") { true }
    dependsOn(prepareSecurityLiveHome)
    finalizedBy(cleanSecurityLiveHome)
    systemProperty("user.home", securityLiveHome.get().asFile.absolutePath)
    systemProperty("heartbeat.security.liveHome", securityLiveHome.get().asFile.absolutePath)
    systemProperty("heartbeat.security.liveTest", "true")
    systemProperty("heartbeat.security.liveReport", securityLiveReport.get().asFile.absolutePath)
    testLogging { events("passed", "failed", "skipped") }
}
