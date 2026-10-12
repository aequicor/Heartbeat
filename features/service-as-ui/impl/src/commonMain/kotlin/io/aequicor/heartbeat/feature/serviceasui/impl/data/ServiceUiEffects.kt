package io.aequicor.heartbeat.feature.serviceasui.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiEffect
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiIntent
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiLibraryStorage
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Storage of the machine; failures are logged by the runtime and mapped by the spec to LoadFailed/PersistFailed. */
@Inject
internal class ServiceUiEffects(private val storage: UiLibraryStorage) :
    EffectHandler<ServiceUiEffect, ServiceUiIntent> {
    override suspend fun handle(effect: ServiceUiEffect, machine: EffectScope<ServiceUiIntent>) {
        when (effect) {
            ServiceUiEffect.Load -> machine.send(ServiceUiIntent.Internal.Loaded(storage.load()))

            is ServiceUiEffect.RetryLoad -> {
                delay(retryDelay(effect.attempt))
                machine.send(ServiceUiIntent.Internal.RetryLoad)
            }

            is ServiceUiEffect.Persist -> {
                storage.save(effect.library)
                machine.send(ServiceUiIntent.Internal.Persisted(effect.writeId))
            }
        }
    }
}

/** One second before the second read, doubling after every failure. */
internal fun retryDelay(attempt: Int): Duration = 1.seconds * (1 shl (attempt - 1).coerceIn(0, MAX_SHIFT))

private const val MAX_SHIFT = 5
