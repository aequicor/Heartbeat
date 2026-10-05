package io.aequicor.heartbeat.feature.plantumlsupport.impl.data

import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlResult
import io.aequicor.heartbeat.feature.plantumlsupport.impl.domain.PlantUmlLimits
import io.aequicor.heartbeat.feature.plantumlsupport.impl.domain.PlantUmlSource

/**
 * Blocking PlantUML drawing of the first page of [PlantUmlSource] with [preamble] config lines.
 * Called one at a time from a dedicated worker; never touches files, URLs or the environment. Returns
 * [PlantUmlResult.Image], [PlantUmlResult.SyntaxError], [PlantUmlResult.Unsupported] or a failure — an image that
 * exceeds [PlantUmlLimits] is a `TooLarge` failure, not a cropped picture.
 */
internal fun interface PlantUmlEngine {
    fun render(source: PlantUmlSource, preamble: List<String>, limits: PlantUmlLimits): PlantUmlResult
}
