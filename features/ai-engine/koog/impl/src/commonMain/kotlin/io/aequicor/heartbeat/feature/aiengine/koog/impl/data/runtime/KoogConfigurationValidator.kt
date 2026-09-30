package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionConfigurationChange
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import io.aequicor.heartbeat.feature.aiengine.koog.api.koogProvider

/** Validates a live selection against the same credential binding; no running request is changed here. */
internal class KoogConfigurationValidator(
    private val access: KoogAccess,
    private val identity: RuntimeIdentity,
    private val route: ExecutionRoute,
) {
    suspend fun resolve(current: SessionConfiguration, change: SessionConfigurationChange): SessionConfiguration {
        if (change is SessionConfigurationChange.Trust) {
            fail(EngineFailure.Engine(EngineFailureReason.UnsupportedCapability))
        }
        val connection = access.route(route.binding, identity)
        val provider = requireNotNull(koogProvider(connection.source))
        return when (change) {
            is SessionConfigurationChange.Model -> {
                val levels = koogCall {
                    access.open(connection, change.model.value).use { client ->
                        if (client.models().none { it.id == change.model.value }) {
                            fail(EngineFailure.Access(AccessFailureReason.ModelAccessDenied))
                        }
                        val ids = listOf(change.model.value)
                        val reported = client.reasoning(ids)
                        if (reported == null) {
                            access.reasoning.levels(provider, change.model.value)
                        } else {
                            access.reasoning.discover(provider, ids, reported)[change.model.value].orEmpty()
                        }
                    }
                }
                current.copy(model = change.model, reasoningEffort = current.reasoningEffort?.takeIf { it in levels })
            }

            is SessionConfigurationChange.Effort -> {
                if (change.effort != null && change.effort !in access.reasoning.levels(provider, current.model.value)) {
                    fail(EngineFailure.Request(RequestFailureReason.Invalid))
                }
                current.copy(reasoningEffort = change.effort)
            }

            is SessionConfigurationChange.Trust -> error("unsupported change was checked")
        }
    }
}
