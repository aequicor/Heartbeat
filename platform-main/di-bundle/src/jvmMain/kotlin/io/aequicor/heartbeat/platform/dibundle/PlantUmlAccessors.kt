package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlRenderer

/**
 * Desktop accessor of the local PlantUML renderer: `(graph as PlantUmlAccessors).plantUml`. Only the JVM graph
 * has it — PlantUML needs the JVM, and mobile hosts keep diagram fences as code.
 */
@ContributesTo(AppScope::class)
interface PlantUmlAccessors {
    /** Draws diagram fences of Markdown while the `plantuml.enabled` toggle is on. */
    val plantUml: PlantUmlRenderer
}
