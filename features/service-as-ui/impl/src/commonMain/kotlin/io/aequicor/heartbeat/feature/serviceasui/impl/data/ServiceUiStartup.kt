package io.aequicor.heartbeat.feature.serviceasui.impl.data

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.profilefacade.ProfileStartup
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiEnabled
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiIntent
import io.aequicor.heartbeat.feature.serviceasui.api.spi.ServiceUiProvider
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.ServiceUiMachine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/**
 * Starts the profile machine with the profile and feeds it the inputs it cannot observe from a state effect, which
 * would be cancelled on every transition: the feature toggle and the owner snapshots of every provider.
 */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class ServiceUiStartup(
    private val machine: Lazy<ServiceUiMachine>,
    @ForScope(ProfileScope::class) private val scope: ScopeHandle,
    private val toggles: FeatureToggles,
    private val providers: Lazy<Set<ServiceUiProvider>>,
) : ProfileStartup {
    private val log = Log.tag("ServiceUiStartup")

    override fun start() {
        val machine = machine.value
        scope.coroutineScope.launch {
            toggles.observe(ServiceUiEnabled)
                .catch { e -> log.e(e) { "Service UI toggle stopped" } }
                .collect { machine.send(ServiceUiIntent.Internal.AvailabilityChanged(it)) }
        }
        for (provider in providers.value) {
            scope.coroutineScope.launch {
                provider.owners
                    .catch { e -> log.e(e) { "Owners of ${provider.kind} stopped" } }
                    .collect { machine.send(ServiceUiIntent.Internal.OwnersChanged(provider.kind, it)) }
            }
        }
        log.d { "Service UI started with providers ${providers.value.map { it.kind }}" }
    }
}
