plugins {
    alias(libs.plugins.heartbeat.bundle)
}

// Бандл «studio»: студия и её спутники — вложения, автодополнение, чек-листы, опросники, планирование, усилие,
// оптимизация контекста, обратная связь, перенос сессий, computer use и service-as-ui. Состав растёт по шагам.
kotlin {
    sourceSets {
        jvmMain.dependencies {
            implementation(projects.features.attachments.api)
            implementation(projects.features.attachments.impl)
            implementation(projects.features.effortConfiguration.api)
            implementation(projects.features.effortConfiguration.impl)
            implementation(projects.features.autocomplete.api)
            implementation(projects.features.autocomplete.impl)
            implementation(projects.features.aiSessionEngineTransfer.api)
            implementation(projects.features.aiSessionEngineTransfer.impl)
            implementation(projects.features.checklist.api)
            implementation(projects.features.checklist.impl)
            implementation(projects.features.scheduler.api)
            implementation(projects.features.questionnaire.api)
            implementation(projects.features.questionnaire.impl)
            implementation(projects.features.planningMode.api)
            implementation(projects.features.planningMode.impl)
            implementation(projects.features.aiEngine.facade.api)
            implementation(projects.features.feedback.api)
            implementation(projects.features.feedback.impl)
            implementation(projects.core.stateMachine.api)
            implementation(projects.core.featureToggles.api)
            implementation(projects.core.di.api)
            implementation(projects.core.common)
            implementation(projects.core.logging)
            implementation(projects.core.mvi)
            implementation(projects.core.datastore.api)
            implementation(projects.core.navigation.api)
            implementation(projects.core.profileFacade.api)
            implementation(projects.features.computerUse.api)
            implementation(projects.features.computerUse.impl)
            implementation(projects.features.contextWindowOptimization.api)
            implementation(projects.features.contextWindowOptimization.impl)
            implementation(projects.core.desktopDialogs)
            implementation(projects.features.serviceAsUi.api)
            implementation(projects.features.serviceAsUi.impl)
            implementation(projects.features.browser.api)
            implementation(projects.features.projectConfig.api)
        }
    }
}
