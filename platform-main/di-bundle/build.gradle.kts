plugins {
    alias(libs.plugins.heartbeat.kmp.compose)
    alias(libs.plugins.heartbeat.metro)
    alias(libs.plugins.kotlinSerialization)
}

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
            implementation(projects.features.researchChat.impl)
            implementation(projects.features.browser.impl)
            implementation(projects.features.searchEngine.impl)
            implementation(projects.features.togglesPanel.impl)
            implementation(projects.features.settings.impl)
            implementation(projects.features.aiEngine.authenticator.impl)
            implementation(projects.features.aiEngine.facade.impl)
            implementation(projects.features.aiEngine.connections.impl)
            implementation(projects.features.aiSessionEngineTransfer.impl)
            implementation(projects.features.effortConfiguration.impl)
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
            implementation(projects.features.autocomplete.api)
            implementation(projects.features.autocomplete.impl)
            implementation(projects.features.organicAi.api)
            implementation(projects.features.organicAi.impl)
            api(projects.features.plantUmlSupport.api)
            implementation(projects.features.plantUmlSupport.impl)
            implementation(projects.features.scheduler.api)
            implementation(projects.features.scheduler.impl)
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
        jvmTest.dependencies {
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
            implementation(projects.features.aiEngine.connections.api)
            implementation(projects.features.aiSessionEngineTransfer.api)
            implementation(projects.features.effortConfiguration.api)
            implementation(projects.features.agentLearning.api)
            implementation(projects.features.scheduler.api)
            implementation(projects.features.organicAi.api)
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
val recoveryHostOs = providers.systemProperty("os.name").get()
val recoveryHostArm = providers.systemProperty("os.arch").get().lowercase() in setOf("arm64", "aarch64")
val recoveryNativeBundle = when {
    recoveryHostOs.startsWith("Mac") && recoveryHostArm -> libs.jcef.macos.arm64
    recoveryHostOs.startsWith("Mac") -> libs.jcef.macos.x64
    recoveryHostOs.startsWith("Windows") && recoveryHostArm -> libs.jcef.windows.arm64
    recoveryHostOs.startsWith("Windows") -> libs.jcef.windows.x64
    else -> null
}
dependencies { recoveryNativeBundle?.let { recoveryNativeRuntime(it) } }

val regularJvmTest = tasks.named<Test>("jvmTest")
val recoveryNativeRoot = layout.buildDirectory.dir("native-research-profile")
val prepareRecoveryNativeProfile = tasks.register<Delete>("prepareBrowserRecoveryNativeProfile") {
    delete(recoveryNativeRoot)
}
val cleanRecoveryNativeProfile = tasks.register<Delete>("cleanBrowserRecoveryNativeProfile") {
    delete(recoveryNativeRoot)
}
tasks.register<Test>("browserRecoveryNativeTest") {
    group = "verification"
    description = "Checks actual Chromium recovery into research and attachment storage in an isolated JVM."
    dependsOn(":features:browser:impl:verifyBrowserRuntime")
    dependsOn(prepareRecoveryNativeProfile)
    finalizedBy(cleanRecoveryNativeProfile)
    testClassesDirs = regularJvmTest.get().testClassesDirs
    classpath = regularJvmTest.get().classpath + recoveryNativeRuntime
    filter { includeTestsMatching("*ResearchBrowserNativeIntegrationTest") }
    maxParallelForks = 1
    forkEvery = 1
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("Native integration must execute in a fresh worker") { true }
    systemProperty("heartbeat.browser.recoveryNativeTest", "true")
    systemProperty("heartbeat.browser.recoveryNativeRoot", recoveryNativeRoot.get().asFile.absolutePath)
    systemProperty("java.awt.headless", "false")
    systemProperty("compose.layers.type", "WINDOW")
    if (recoveryHostOs.startsWith("Windows")) {
        systemProperty("skiko.renderApi", if (recoveryHostArm) "SOFTWARE_FAST" else "OPENGL")
    }
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(libs.versions.desktop.jdk.get().toInt()))
        vendor.set(JvmVendorSpec.JETBRAINS)
    })
    jvmArgs("--limit-modules=java.se,jdk.unsupported,jdk.httpserver,jdk.crypto.ec,jdk.zipfs,jdk.attach")
    if (recoveryHostOs.startsWith("Mac")) {
        jvmArgs(
            "--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
        )
    }
}
