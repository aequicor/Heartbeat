package io.aequicor.heartbeat.ds.layouts

import androidx.compose.runtime.compositionLocalOf

/**
 * Whether a workspace can show a pane alongside its neighbors. Compact hosts provide false when
 * the pane already fills the available content area, making an expand/restore action ineffective.
 * This measures the parent workspace rather than the whole window or the pane's own width.
 */
val LocalPaneExpansionAvailable = compositionLocalOf { true }
