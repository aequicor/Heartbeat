package io.aequicor.heartbeat.ds.theme

import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.ds.tokens.HbStudioDimensions

/** Desktop defaults to dense mouse controls; touch platforms retain their larger hit targets. */
internal expect fun defaultHbDimensions(): HbDimensions

/** Studio density follows the host platform, independently of native-kit previews. */
internal expect fun defaultHbStudioDimensions(): HbStudioDimensions
