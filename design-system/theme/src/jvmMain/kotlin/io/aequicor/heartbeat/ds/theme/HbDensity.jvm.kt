package io.aequicor.heartbeat.ds.theme

import io.aequicor.heartbeat.ds.tokens.HbDimensions

internal actual fun defaultHbDimensions(): HbDimensions = HbDimensions().let { it.copy(touchTarget = it.controlHeight) }
