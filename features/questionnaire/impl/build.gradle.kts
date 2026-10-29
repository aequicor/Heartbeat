plugins {
    alias(libs.plugins.heartbeat.kmp.compose)
    alias(libs.plugins.heartbeat.metro)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.features.questionnaire.api)
            implementation(projects.features.aiStudio.api)
            implementation(projects.features.questionnaire.screenUi)
            implementation(projects.core.common)
            implementation(projects.core.datastore.api)
            implementation(projects.core.di.ext)
            implementation(projects.core.mvi)
            implementation(projects.core.stateMachine.flowmviExt)
            implementation(projects.core.navigation.compose)
            implementation(projects.core.featureToggles.api)
            implementation(projects.core.profileFacade.api)
            implementation(projects.core.logging)
        }
        commonTest.dependencies { implementation(libs.flowmvi.test) }
    }
}
