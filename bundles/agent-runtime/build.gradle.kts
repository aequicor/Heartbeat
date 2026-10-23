plugins {
    alias(libs.plugins.heartbeat.bundle)
}

// Бандл «agent-runtime»: рантайм агентов — harness, organic-ai, планировщик, адаптивные субагенты, режим worktree,
// конфигурация проекта и обучение агента.
kotlin {
    sourceSets {
        jvmMain.dependencies {
            implementation(projects.features.harness.api)
            implementation(projects.features.harness.impl)
            implementation(projects.features.organicAi.api)
            implementation(projects.features.organicAi.impl)
            implementation(projects.features.scheduler.api)
            implementation(projects.features.scheduler.impl)
            implementation(projects.features.adaptiveSubagents.api)
            implementation(projects.features.adaptiveSubagents.impl)
            implementation(projects.features.worktreeMode.api)
            implementation(projects.features.worktreeMode.impl)
            implementation(projects.features.projectConfig.api)
            implementation(projects.features.projectConfig.impl)
            implementation(projects.features.agentLearning.api)
            implementation(projects.features.agentLearning.impl)
            implementation(projects.features.aiEngine.facade.api)
            implementation(projects.features.aiEngine.connections.api)
            implementation(projects.features.aiEngine.authenticator.api)
            implementation(projects.features.searchEngine.api)
            implementation(projects.features.serviceAsUi.api)
            implementation(projects.features.aiStudio.api)
            implementation(projects.features.attachments.api)
            implementation(projects.core.desktopDialogs)
            implementation(projects.core.stateMachine.api)
            implementation(projects.core.featureToggles.api)
            implementation(projects.core.di.api)
            implementation(projects.core.common)
            implementation(projects.core.logging)
            implementation(projects.core.mvi)
            implementation(projects.core.datastore.api)
            implementation(projects.core.navigation.api)
            implementation(projects.core.network.api)
            implementation(projects.core.secrets.api)
            implementation(projects.core.profileFacade.api)
        }
    }
}
