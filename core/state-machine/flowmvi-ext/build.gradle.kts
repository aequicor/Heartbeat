plugins {
    alias(libs.plugins.heartbeat.kmp.library)
}

// Экстеншены FlowMVI: стор отражает состояние и outputs машины, интенты стора уходят в машину.
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.stateMachine.api)
            api(libs.flowmvi.core)
            implementation(projects.core.logging)
        }
        commonTest.dependencies {
            implementation(libs.flowmvi.test)
        }
    }
}
