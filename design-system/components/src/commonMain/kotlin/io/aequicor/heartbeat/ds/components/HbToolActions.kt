package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.ds.layouts.HbFlowRow
import io.aequicor.heartbeat.ds.theme.HbTheme

private val toolActionLog = Log.tag("DS/ToolAction")

/**
 * Commands of [call] in a wrapping row; each button is tagged with its action id. Labels are controls, not
 * transcript prose, so the surrounding selection neither copies them nor starts on a long press.
 */
@Composable
internal fun HbToolActions(call: HbToolCall, onAction: (HbToolAction) -> Unit, modifier: Modifier = Modifier) {
    if (call.actions.isEmpty()) return
    DisableSelection {
        HbFlowRow(modifier.fillMaxWidth(), gap = HbTheme.spacing.s) {
            call.actions.forEach { action ->
                key(action.id) {
                    HbButton(
                        text = action.label,
                        onClick = {
                            toolActionLog.i { "tool action requested tool=${call.id} action=${action.id}" }
                            onAction(action)
                        },
                        modifier = Modifier.testTag(action.id),
                        style = action.style,
                        size = HbButtonSize.Small,
                    )
                }
            }
        }
    }
}
