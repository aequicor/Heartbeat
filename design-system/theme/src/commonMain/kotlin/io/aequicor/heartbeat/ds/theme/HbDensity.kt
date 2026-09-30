package io.aequicor.heartbeat.ds.theme

import io.aequicor.heartbeat.ds.tokens.HbDimensions

/**
 * Dimension preset of the actual host: dense mouse geometry on desktop,
 * touch geometry elsewhere. Independent of which native control kit is previewed.
 */
internal expect fun defaultHbDimensions(): HbDimensions
