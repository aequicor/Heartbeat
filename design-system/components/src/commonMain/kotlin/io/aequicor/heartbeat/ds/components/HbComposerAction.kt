package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.Immutable

/** A localized menu command. The owner supplies behavior through [HbComposerMenuButton]'s callback. */
@Immutable
public data class HbComposerAction(
    val id: String,
    val label: String,
    val supportingText: String? = null,
    val isEnabled: Boolean = true,
    val sectionLabel: String? = null,
)
