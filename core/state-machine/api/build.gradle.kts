plugins {
    alias(libs.plugins.heartbeat.kmp.library)
    alias(libs.plugins.kotlinSerialization) // @Serializable test states (persist); main code only uses KSerializer
}

// Контракт state-machine: ключи, ссылки, реестр, DSL спецификации и её чистая семантика. Без движка и без UI —
// его видят api фич.
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.di.api)
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.core)
        }
    }
}
