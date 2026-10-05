package io.aequicor.heartbeat.ds.components

/**
 * Text diagram languages a Markdown fence can carry. A complete fence in one of them becomes a single
 * [HbMarkdownBlockKind.Diagram] row; whether it is drawn as an image is up to the host's diagram renderer,
 * otherwise the row shows its source as code.
 */
public enum class HbDiagramLanguage {
    PlantUml,
}

/**
 * A diagram row stays one lazy item even when shown as source (no renderer, mobile hosts, errors), so it is bounded
 * like a small group of code chunks; larger fences stay segmented code. Agent diagrams are far smaller.
 */
internal const val MAX_DIAGRAM_LINES = 160
internal const val MAX_DIAGRAM_CHARACTERS = 16 * 1024

private val PlantUmlFenceNames = setOf("plantuml", "puml")

/**
 * PlantUML start directives recognized in an untagged fence: diagram types drawn in-process. Directives that
 * need external tools (for example `@startdot`) are not listed and remain code.
 */
private val PlantUmlStart = Regex(
    "^@start(uml|mindmap|wbs|gantt|json|yaml|salt|ebnf|regex|chen)\\b",
    RegexOption.IGNORE_CASE,
)

/**
 * Detects the diagram language of a complete fence: an explicit `plantuml`/`puml` info string (case-insensitive,
 * first word), or an untagged fence whose first non-blank line is a known PlantUML start directive.
 * Oversized sources stay segmented code.
 */
internal fun diagramLanguage(fenceLanguage: String?, source: String): HbDiagramLanguage? {
    if (source.length > MAX_DIAGRAM_CHARACTERS || source.count { it == '\n' } >= MAX_DIAGRAM_LINES) return null
    val name = fenceLanguage?.trim()?.substringBefore(' ')?.lowercase().orEmpty()
    val isPlantUml = if (name.isEmpty()) {
        source.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.let(PlantUmlStart::containsMatchIn) == true
    } else {
        name in PlantUmlFenceNames
    }
    return if (isPlantUml) HbDiagramLanguage.PlantUml else null
}
