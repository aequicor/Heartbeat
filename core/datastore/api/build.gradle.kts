plugins {
    alias(libs.plugins.heartbeat.kmp.library)
}

// Контракт хранения: DataStores (key-value + Room-БД фичи) с владельцем app/profile, удержание записей
// (Retention: срок / событие), колонки удержания строк БД. Файлы, таймеры и чистка — в :core:datastore:impl.
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.profileFacade.api)
            api(libs.androidx.room.runtime)
            api(libs.kotlinx.datetime)
            api(libs.kotlinx.serialization.core)
            api(libs.kotlinx.coroutines.core)
        }
    }
}
