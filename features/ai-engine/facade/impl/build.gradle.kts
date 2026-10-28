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
    // Mobile fallbacks (Android, iOS) that Desktop must not compile: a desktop graph would resolve them instead of
    // the Desktop implementations.
    sourceSets.androidMain { kotlin.srcDir("src/mobileMain/kotlin") }
    sourceSets.iosMain { kotlin.srcDir("src/mobileMain/kotlin") }
    sourceSets {
        commonMain.dependencies {
            implementation(projects.features.aiEngine.facade.api)
            implementation(projects.features.aiEngine.authenticator.api)
            implementation(projects.features.githubConnector.api)
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

// Preserve useful diagnostics from the local JVM suite.
val regularJvmTest = tasks.named<Test>("jvmTest")
regularJvmTest.configure {
    // Preserve child-process exit codes and assertion messages in remote test logs; stdout stays opt-in.
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
