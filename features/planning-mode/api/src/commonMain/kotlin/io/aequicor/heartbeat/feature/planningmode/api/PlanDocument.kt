package io.aequicor.heartbeat.feature.planningmode.api

/**
 * The text of [text] after [edit]; the caller checks the result against [PlanLimits.MAX_CHARS]. Total: a section
 * edit with a heading that [isPlanSectionHeading] rejects leaves [text] unchanged.
 */
public fun applyEdit(text: String, edit: PlanEdit): String = when (edit) {
    is PlanEdit.Replace -> edit.text.trimEnd()
    is PlanEdit.Section -> replaceSection(text, edit.heading.trim(), edit.text.trim())
}

/** Whether [heading] is one markdown ATX heading line (`#` .. `######`, then a space or nothing), e.g. `## Steps`. */
public fun isPlanSectionHeading(heading: String): Boolean =
    heading.none { it == '\n' || it == '\r' } && sectionLevel(heading.trim()) != null

/** The first markdown heading of [text] without its marks, or null. */
public fun planTitle(text: String): String? {
    val lines = text.lines()
    val index = headingLevels(lines).indexOfFirst { it != null }
    return lines.getOrNull(index)?.trim()?.trimStart('#')?.trim()?.takeIf { it.isNotEmpty() }
}

/**
 * The block that attaches [plan] to the agent's context: the rules of its mode and the text, cut to [budget]
 * characters with a pointer to [PlanningTools.READ]. Null for a detached plan.
 */
public fun renderPlanBlock(plan: Plan, budget: Int = PlanLimits.DELIVERY_CHARS): String? {
    val rules = when (plan.mode) {
        PlanMode.Planning -> PLANNING_RULES
        PlanMode.Implementing -> IMPLEMENTING_RULES
        PlanMode.Detached -> return null
    }
    val status = when (plan.status) {
        PlanStatus.Draft -> "draft"
        PlanStatus.Proposed -> "proposed, waiting for the user's review"
        PlanStatus.Approved -> "approved by the user"
    }
    val body = when {
        plan.text.isBlank() -> "(the plan is empty)"

        plan.text.length <= budget -> plan.text

        else -> plan.text.take(budget).trimEnd() +
            "\n\n[The plan continues: call ${PlanningTools.READ} to read the whole revision.]"
    }
    return "$rules\nCurrent plan: revision ${plan.revision}, $status.\n<plan>\n$body\n</plan>"
}

private val PLANNING_RULES = """
    Planning mode is on in this chat. Develop the requirements and the plan of the task together with the user:
    explore the workspace, ask questions and record decisions. You may only read: edits, commands that change
    anything and other mutating tools are refused until the user starts the implementation.
    Keep the plan current with ${PlanningTools.WRITE} (pass the revision you last read), re-read it with
    ${PlanningTools.READ} whenever the user may have changed it, and call ${PlanningTools.PROPOSE} when a revision is
    ready for review; then end the turn and wait for the user.
""".trimIndent()

private val IMPLEMENTING_RULES = """
    This chat implements the plan below, developed and approved in planning mode. Follow it; when the work departs
    from it, update the plan with ${PlanningTools.WRITE} (pass the revision you last read) and tell the user why.
    Re-read it with ${PlanningTools.READ} when it may have changed.
""".trimIndent()

private fun replaceSection(text: String, heading: String, body: String): String {
    val level = sectionLevel(heading) ?: return text
    val lines = text.lines()
    val levels = headingLevels(lines)
    val start = lines.indices.firstOrNull { levels[it] != null && lines[it].trim() == heading }
        ?: return appendSection(text, heading, body)
    val end = (start + 1 until lines.size)
        .firstOrNull { index -> levels[index]?.let { it <= level } == true }
        ?: lines.size
    val before = lines.subList(0, start).joinToString("\n").trimEnd()
    val after = lines.subList(end, lines.size).joinToString("\n").trim()
    val section = if (body.isEmpty()) "" else "$heading\n\n$body"
    return listOf(before, section, after).filter { it.isNotEmpty() }.joinToString("\n\n")
}

private fun appendSection(text: String, heading: String, body: String): String {
    val head = text.trimEnd()
    return when {
        body.isEmpty() -> text
        head.isEmpty() -> "$heading\n\n$body"
        else -> "$head\n\n$heading\n\n$body"
    }
}

/** Heading level of every line of [lines]; lines inside fenced code blocks are never headings. */
private fun headingLevels(lines: List<String>): List<Int?> {
    var fence: String? = null
    return lines.map { line ->
        val trimmed = line.trimStart()
        val marker = FENCES.firstOrNull { trimmed.startsWith(it) }
        when {
            fence != null -> null.also { if (marker == fence) fence = null }
            marker != null -> null.also { fence = marker }
            else -> headingLevel(line)
        }
    }
}

/** Level of a trimmed [heading] when it is one heading line, as [isPlanSectionHeading] requires, or null. */
private fun sectionLevel(heading: String): Int? =
    headingLevel(heading)?.takeIf { heading.none { char -> char == '\n' || char == '\r' } }

/** Level of a markdown ATX heading (`#` .. `######` followed by a space), or null. */
private fun headingLevel(line: String): Int? {
    val trimmed = line.trimStart()
    val marks = trimmed.takeWhile { it == '#' }.length
    val isHeading = marks in 1..MAX_HEADING_LEVEL && (trimmed.length == marks || trimmed[marks] == ' ')
    return marks.takeIf { isHeading }
}

private const val MAX_HEADING_LEVEL = 6
private val FENCES = listOf("```", "~~~")
