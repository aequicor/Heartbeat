package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector

/** A localized menu command; [isSelected] marks the current choice visually and for assistive technologies. */
@Immutable
public data class HbComposerAction(
    val id: String,
    val label: String,
    val supportingText: String? = null,
    val isEnabled: Boolean = true,
    val sectionLabel: String? = null,
    val isSelected: Boolean = false,
    val leadingIcon: ImageVector? = null,
)
