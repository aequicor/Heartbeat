package io.aequicor.heartbeat.feature.agentlearning.impl.domain

import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.feature.agentlearning.api.AgentLearningIntent
import io.aequicor.heartbeat.feature.agentlearning.api.AgentLearningOutput
import io.aequicor.heartbeat.feature.agentlearning.api.AgentLearningState

/** The registry machine of the profile, used by the feature's tools and screens. */
internal typealias LearningMachine = Machine<AgentLearningState, AgentLearningIntent, AgentLearningOutput>
