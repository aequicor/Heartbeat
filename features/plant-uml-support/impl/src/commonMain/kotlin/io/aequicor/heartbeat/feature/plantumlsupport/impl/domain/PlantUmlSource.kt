package io.aequicor.heartbeat.feature.plantumlsupport.impl.domain

/**
 * Diagram types the bundled engine draws, by their `@start…` directive. Types that launch external tools (`@startdot`)
 * or need libraries outside the MIT build are not listed. Kept in step with the design system's detection of
 * untagged fences.
 */
internal enum class PlantUmlDiagramType(val directive: String) {
    Uml("uml"),
    MindMap("mindmap"),
    Wbs("wbs"),
    Gantt("gantt"),
    Json("json"),
    Yaml("yaml"),
    Salt("salt"),
    Ebnf("ebnf"),
    Regex("regex"),
    Chen("chen"),
}

/**
 * A fence source prepared for the engine. [text] always contains a start directive, on 0-based line [startLine]: a
 * fence without one is wrapped in `@startuml`/`@enduml`, which adds [addedLines] lines before the author's first line.
 */
internal data class PlantUmlSource(
    val text: String,
    val type: PlantUmlDiagramType,
    val startLine: Int = 0,
    val addedLines: Int = 0,
) {
    /** The 1-based fence line of a 0-based engine line position, or null when it points into added lines. */
    fun fenceLine(position: Int): Int? = (position - addedLines + 1).takeIf { it >= 1 }

    /** Config lines carry the position of the start directive: an error there comes from the host's preamble. */
    fun isPreambleLine(position: Int): Boolean = position == startLine

    companion object {
        private val StartDirective = Regex("^@start([a-z]+)", RegexOption.IGNORE_CASE)

        /** The ELK layout is not in the MIT build; PlantUML would print class-loading failures to stderr. */
        private val ElkLayout = Regex(
            "^\\s*!pragma\\s+layout\\s+elk\\b",
            setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
        )

        /**
         * Prepares [source], or returns null when it is not drawn here: a start directive of another type or a
         * layout engine the build does not contain.
         */
        fun parse(source: String): PlantUmlSource? {
            val text = source.replace("\r\n", "\n").replace('\r', '\n')
            if (ElkLayout.containsMatchIn(text)) return null
            val lines = text.lines().map { it.trim() }
            val startLine = lines.indexOfFirst { it.startsWith("@start", ignoreCase = true) }
            if (startLine < 0) {
                return PlantUmlSource("@startuml\n$text\n@enduml", PlantUmlDiagramType.Uml, addedLines = 1)
            }
            val directive = StartDirective.find(lines[startLine])?.groupValues?.get(1)?.lowercase() ?: return null
            val type = PlantUmlDiagramType.entries.firstOrNull { it.directive == directive } ?: return null
            return PlantUmlSource(text, type, startLine)
        }
    }
}
