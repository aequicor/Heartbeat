package io.aequicor.heartbeat.feature.scheduler.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.scheduler.api.RequestInitiator

/** Capture only the facade's exact request; legacy callers cannot establish correlation from a turn id. */
internal fun AgentToolContext.initiator(): RequestInitiator? = request?.let { RequestInitiator(session, it) }
