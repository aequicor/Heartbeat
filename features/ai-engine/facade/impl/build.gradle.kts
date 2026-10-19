plugins {
    alias(libs.plugins.heartbeat.kmp.library)
    alias(libs.plugins.heartbeat.metro)
    alias(libs.plugins.heartbeat.room)
    alias(libs.plugins.kotlinSerialization)
}

// Реализация фасада ИИ-движков профиля: каталог движков и подключений, выбор движка по умолчанию, модели,
// индекс сессий, пул runtime, активные сессии на машине ActiveSessionMachineSpec и управление движками
// (проверенные загрузки релизов, управляемые копии CLI). Подключается только в :platform-main:di-bundle.
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.features.aiEngine.facade.api)
            implementation(projects.features.aiEngine.authenticator.api)
            implementation(projects.core.featureToggles.api)
            implementation(projects.core.datastore.api)
            implementation(projects.core.di.api)
            implementation(projects.core.common)
            implementation(projects.core.logging)
            implementation(projects.core.network.api)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.okio)
        }
        jvmTest.dependencies {
            implementation(libs.androidx.sqlite.bundled)
            implementation(libs.ktor.client.mock)
        }
    }
}

// Opt-in provider regression; credentials are read from an operator-owned file, never Gradle properties.
val regularJvmTest = tasks.named<Test>("jvmTest")
tasks.register<Test>("safetyLiveTest") {
    group = "verification"
    description = "Exercises automatic tool safety and a real Git merge against an OpenAI-compatible reviewer."
    testClassesDirs = regularJvmTest.get().testClassesDirs
    classpath = regularJvmTest.get().classpath
    filter { includeTestsMatching("*.LocalGitSafetyE2eTest") }
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("Live checks must reach the configured provider") { true }
    systemProperty("heartbeat.safety.liveTest", "true")
    testLogging { events("passed", "failed", "skipped") }
}
