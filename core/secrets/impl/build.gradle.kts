plugins {
    alias(libs.plugins.heartbeat.kmp.library)
    alias(libs.plugins.heartbeat.metro)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.secrets.api)
            implementation(projects.core.di.api)
            implementation(projects.core.common)
            implementation(projects.core.logging)
            implementation(projects.core.profileFacade.api)
            implementation(projects.core.datastore.api)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.okio)
        }
        jvmMain.dependencies {
            implementation(libs.jna.platform)
        }
    }
}

// Explicit native verification: requires a running, unlocked system keyring on Linux/macOS.
// Ordinary jvmTest keeps skipping this test and never opens a user's credential store.
val regularJvmTest = tasks.named<Test>("jvmTest")
tasks.register<Test>("nativeSecretsTest") {
    group = "verification"
    description = "Verifies the native system credential store with isolated synthetic profile entries."
    testClassesDirs = regularJvmTest.get().testClassesDirs
    classpath = regularJvmTest.get().classpath
    javaLauncher.set(regularJvmTest.flatMap { it.javaLauncher })
    filter { includeTestsMatching("*JvmProtectedVaultTest") }
    systemProperty("heartbeat.test.nativeSecrets", "true")
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("Native credential-store verification must run against the current session") { true }
}
