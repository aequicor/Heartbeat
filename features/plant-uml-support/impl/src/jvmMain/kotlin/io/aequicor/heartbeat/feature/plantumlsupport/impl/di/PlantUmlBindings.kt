package io.aequicor.heartbeat.feature.plantumlsupport.impl.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlRenderer
import io.aequicor.heartbeat.feature.plantumlsupport.impl.data.DefaultPlantUmlRenderer
import io.aequicor.heartbeat.feature.plantumlsupport.impl.data.ProcessPlantUmlEngine
import io.aequicor.heartbeat.feature.plantumlsupport.impl.domain.PlantUmlEnabled

/**
 * Desktop-only wiring: PlantUML needs the JVM, so the renderer and its toggle exist only in the desktop graph.
 * Drawings are sent from one IO thread in the application scope to a worker process that ends with that scope.
 */
@BindingContainer
@ContributesTo(AppScope::class)
public object PlantUmlBindings {
    /** The renderer; its worker process starts on the first drawing. */
    @Provides
    @SingleIn(AppScope::class)
    internal fun renderer(
        toggles: FeatureToggles,
        dispatchers: DispatcherProvider,
        @ForScope(AppScope::class) app: ScopeHandle,
    ): PlantUmlRenderer = DefaultPlantUmlRenderer(
        engine = ProcessPlantUmlEngine(scope = app.coroutineScope, timers = dispatchers.default),
        toggles = toggles,
        scope = app.coroutineScope,
        worker = dispatchers.io.limitedParallelism(1),
    )

    /** Registers [PlantUmlEnabled] for the toggles panel. */
    @Provides
    @IntoSet
    public fun enabled(): FeatureToggle<*> = PlantUmlEnabled
}
