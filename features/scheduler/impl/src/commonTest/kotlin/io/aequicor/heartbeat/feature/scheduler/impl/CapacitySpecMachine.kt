package io.aequicor.heartbeat.feature.scheduler.impl

import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.scheduler.api.BackgroundCapacityIntent
import io.aequicor.heartbeat.feature.scheduler.api.BackgroundCapacityMachineSpec
import io.aequicor.heartbeat.feature.scheduler.api.BackgroundCapacityOutput
import io.aequicor.heartbeat.feature.scheduler.api.BackgroundCapacityState
import io.aequicor.heartbeat.feature.scheduler.impl.data.BackgroundCapacityMachine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

internal class CapacitySpecMachine : BackgroundCapacityMachine {
    override val name: String = BackgroundCapacityMachineSpec.name
    override val state = MutableStateFlow<BackgroundCapacityState>(BackgroundCapacityState.Ready())
    private val emitted = MutableSharedFlow<BackgroundCapacityOutput>(extraBufferCapacity = 16)
    override val outputs: Flow<BackgroundCapacityOutput> = emitted

    override suspend fun send(intent: BackgroundCapacityIntent): SendResult {
        val resolution = BackgroundCapacityMachineSpec.resolve(state.value, intent) ?: return SendResult.Ignored
        state.value = resolution.to
        resolution.outputs.forEach { emitted.emit(it) }
        return SendResult.Accepted
    }
}
