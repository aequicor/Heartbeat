package io.aequicor.heartbeat.feature.aiengine.facade.impl.di

import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.secrets.SecretStore
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.VaultConfigSecrets
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.EngineConfigurationService
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.EngineFlags
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.EnginePreferences
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.EngineRegistry
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.FacadeContext

/** Engine configuration of the profile: settings, project files, trust and imports behind the control panel. */
@ContributesTo(ProfileScope::class)
@BindingContainer
object AiEngineConfigurationBindings {
    /** The configuration service; one per profile, saves are serialized inside it. */
    @Provides
    @SingleIn(ProfileScope::class)
    fun service(
        registry: EngineRegistry,
        flags: EngineFlags,
        preferences: EnginePreferences,
        secrets: SecretStore,
        workspaces: LocalWorkspaces,
        context: FacadeContext,
    ): EngineConfigurationService = EngineConfigurationService(
        registry,
        flags,
        preferences,
        VaultConfigSecrets(secrets),
        workspaces,
        context,
    )

    /** Public configuration contract. */
    @Provides
    fun configuration(service: EngineConfigurationService): EngineConfiguration = service
}
