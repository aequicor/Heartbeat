package io.aequicor.heartbeat.feature.projectconfig.impl.domain

import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.feature.projectconfig.api.ProjectConfigIntent
import io.aequicor.heartbeat.feature.projectconfig.api.ProjectConfigOutput
import io.aequicor.heartbeat.feature.projectconfig.api.ProjectConfigState

/** The profile machine of project configuration, used by the feature's watchers and screens. */
internal typealias ProjectConfigMachine = Machine<ProjectConfigState, ProjectConfigIntent, ProjectConfigOutput>
