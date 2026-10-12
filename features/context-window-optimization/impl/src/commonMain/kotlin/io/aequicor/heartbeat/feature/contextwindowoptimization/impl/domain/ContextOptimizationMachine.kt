package io.aequicor.heartbeat.feature.contextwindowoptimization.impl.domain

import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationIntent
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationOutput
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationState

/** The context optimization machine of the profile, used by the feature's own screens. */
internal typealias ContextOptimizationMachine =
    Machine<ContextOptimizationState, ContextOptimizationIntent, ContextOptimizationOutput>
