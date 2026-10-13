package io.aequicor.heartbeat.feature.checklist.screenmodel

import kotlinx.collections.immutable.toImmutableList

/** Optimistic editing only; completion, persistence and delivery always wait for the owning machine. */
fun ChecklistScreenState.predictDraft(intent: ChecklistScreenIntent): ChecklistScreenState {
    if (phase != ChecklistPhaseUi.Open || hasFailed) return this
    if (intent !is ChecklistScreenIntent.Text) return this
    val next = fields.map { field ->
        if (field.id == intent.field && field.isText) field.copy(text = intent.text) else field
    }.toImmutableList()
    return copy(fields = next, isCompletionAllowed = next.all { it.isComplete() })
}

private fun ChecklistFieldUi.isComplete(): Boolean = when {
    !isRequired && text.isBlank() && selected.isEmpty() -> true
    isText -> text.isNotBlank()
    isMultiple -> selected.size in min..max
    else -> selected.size == 1
}
