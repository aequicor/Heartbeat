package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeFactory
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.statemachine.MachineLauncher
import io.aequicor.heartbeat.feature.aiengine.codex.api.CodexLocalConfiguration
import io.aequicor.heartbeat.feature.searchengine.api.SearchEngine

@Inject
internal data class CodexRuntimeEnvironment(
    val config: CodexLocalConfiguration,
    val toggles: FeatureToggles,
    val dispatchers: DispatcherProvider,
    val launcher: MachineLauncher,
    val scopes: ScopeFactory,
    val search: SearchEngine,
    @ForScope(ProfileScope::class) val profile: ScopeHandle,
)
