package io.aequicor.heartbeat.feature.harness.impl.domain

import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.feature.harness.api.HarnessIntent
import io.aequicor.heartbeat.feature.harness.api.HarnessOutput
import io.aequicor.heartbeat.feature.harness.api.HarnessState

/** The profile library outlives individual enabled toggle branches and their observers. */
internal typealias HarnessMachine = Machine<HarnessState, HarnessIntent, HarnessOutput>
