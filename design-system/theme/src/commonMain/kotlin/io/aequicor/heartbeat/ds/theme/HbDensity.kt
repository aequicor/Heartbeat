package io.aequicor.heartbeat.ds.theme

import io.aequicor.heartbeat.ds.tokens.HbDimensions

/** Desktop defaults to dense mouse controls; touch platforms retain their larger hit targets. */
internal expect fun defaultHbDimensions(): HbDimensions
