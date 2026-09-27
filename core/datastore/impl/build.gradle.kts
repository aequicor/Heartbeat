plugins {
    alias(libs.plugins.heartbeat.kmp.library)
    alias(libs.plugins.heartbeat.metro)
    alias(libs.plugins.kotlinSerialization) // journal of fired events, @Serializable test values
    alias(libs.plugins.ksp) // Room compiler for the test database (jvmTest)
}

// Хранилища: файлы per-owner, DataStore Preferences, открытие Room-БД фич, удержание записей (таймеры, события),
// логи DS/DB, постоянный ActiveProfileStorage. Подключается только в :platform-main:di-bundle.
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.datastore.api)
            implementation(projects.core.di.api)
            implementation(projects.core.common)
            implementation(projects.core.logging)
            implementation(projects.core.profileFacade.api)
            implementation(libs.androidx.datastore.preferencesCore)
            implementation(libs.androidx.sqlite.bundled)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.okio)
        }
    }
}

dependencies {
    add("kspJvmTest", libs.androidx.room.compiler)
}
