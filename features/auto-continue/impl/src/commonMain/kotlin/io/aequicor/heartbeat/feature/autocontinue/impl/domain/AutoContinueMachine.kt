package io.aequicor.heartbeat.feature.autocontinue.impl.domain

import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueIntent
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueOutput
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueState

/** The auto-continue machine of the profile, used by the feature's own screens. */
internal typealias AutoContinueMachine = Machine<AutoContinueState, AutoContinueIntent, AutoContinueOutput>
