plugins {
    alias(libs.plugins.heartbeat.bundle)
}

// Бандл «agent-runtime»: рантайм агентов — режим worktree и обучение агента (остальные фичи группы переедут сюда же).
kotlin {
    sourceSets {
        jvmMain.dependencies {
            implementation(projects.features.worktreeMode.api)
            implementation(projects.features.worktreeMode.impl)
            implementation(projects.features.agentLearning.api)
            implementation(projects.features.agentLearning.impl)
            implementation(projects.features.projectConfig.api)
            implementation(projects.features.aiEngine.facade.api)
            implementation(projects.core.stateMachine.api)
            implementation(projects.core.featureToggles.api)
            implementation(projects.core.di.api)
            implementation(projects.core.common)
            implementation(projects.core.logging)
            implementation(projects.core.mvi)
            implementation(projects.core.datastore.api)
            implementation(projects.core.navigation.api)
            implementation(projects.core.profileFacade.api)
        }
    }
}
