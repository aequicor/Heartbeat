package io.aequicor.heartbeat.feature.checklist.impl.data

/** Decoder exceptions can include user answers. Preserve only the exception kind. */
internal fun Exception.withoutChecklistText(): IllegalArgumentException =
    IllegalArgumentException("Invalid checklist data (${this::class.simpleName.orEmpty()})")
