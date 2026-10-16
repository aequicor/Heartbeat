package io.aequicor.heartbeat.feature.applogs.impl.data

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.feature.applogs.api.AppLogsEffect
import io.aequicor.heartbeat.feature.applogs.api.AppLogsEnabled
import io.aequicor.heartbeat.feature.applogs.api.AppLogsIntent
import io.aequicor.heartbeat.feature.applogs.api.AppLogsOutcome
import io.aequicor.heartbeat.feature.applogs.impl.domain.AppLogsActions
import kotlinx.coroutines.CancellationException

/** Enforces rollout availability and reports safe outcomes; raw native exception messages never enter logs. */
class AppLogsEffects(private val actions: AppLogsActions, private val toggles: FeatureToggles) :
    EffectHandler<AppLogsEffect, AppLogsIntent> {
    private val log = Log.tag("AppLogsEffects")

    override suspend fun handle(effect: AppLogsEffect, machine: EffectScope<AppLogsIntent>) {
        val result = try {
            if (toggles.get(AppLogsEnabled)) {
                when (effect) {
                    is AppLogsEffect.Execute -> actions.execute(effect.operation)
                }
            } else {
                AppLogsOutcome.Disabled
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(error.redactedFailure()) { "Native log operation failed" }
            AppLogsOutcome.Failure
        }
        machine.send(AppLogsIntent.Internal.Finished(result))
    }
}
