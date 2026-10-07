package io.aequicor.heartbeat.feature.scheduler.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId

/** Stable native delivery identity, shared by wake submission and exact owner admission after recovery. */
public fun WakeId.deliveryRequestId(): RequestId = RequestId("wake_$value")

internal fun isValidEventFeatureName(name: String): Boolean = name.matches(Regex("[a-z][a-z0-9_-]{0,63}"))
