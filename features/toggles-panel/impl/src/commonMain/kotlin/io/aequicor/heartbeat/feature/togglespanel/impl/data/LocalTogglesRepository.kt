package io.aequicor.heartbeat.feature.togglespanel.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggleControl
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.togglespanel.api.ToggleOperation
import io.aequicor.heartbeat.feature.togglespanel.impl.di.scope.TogglesPanelScope
import io.aequicor.heartbeat.feature.togglespanel.impl.domain.TogglesRepository

/** Data adapter using the existing DataStore-backed toggle controller. */
@Inject
@ContributesBinding(TogglesPanelScope::class)
class LocalTogglesRepository(private val control: FeatureToggleControl) : TogglesRepository {
    private val log = Log.tag("LocalTogglesRepository")

    override fun observeStates() = control.observeStates().also { log.d { "observe local flags" } }
    override suspend fun apply(operation: ToggleOperation) {
        log.d { "apply local flag operation" }
        when (operation) {
            is ToggleOperation.SetFlag -> control.setOverride(operation.toggle, operation.isEnabled)
            is ToggleOperation.SetChoice -> control.setOverride(operation.toggle, operation.value)
            is ToggleOperation.Reset -> control.reset(operation.toggle)
            ToggleOperation.ResetAll -> control.resetAll()
        }
    }
}
