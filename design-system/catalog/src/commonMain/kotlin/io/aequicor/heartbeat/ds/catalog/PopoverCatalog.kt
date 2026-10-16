package io.aequicor.heartbeat.ds.catalog

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbPopover
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.resources.HbString
import io.aequicor.heartbeat.ds.resources.hbString

/** Interactive sheet with selected trigger, disabled bounds, long localized text and keyboard focus targets. */
@Composable
internal fun PopoverExample(modifier: Modifier = Modifier) {
    var isOpen by remember { mutableStateOf(false) }
    var percent by remember { mutableIntStateOf(100) }
    val label = hbString(HbString.MoreActions)
    Box(modifier) {
        HbIconButton(HbIcons.ZoomIn, label, { isOpen = !isOpen }, isSelected = isOpen)
        HbPopover(isOpen, { isOpen = false }, label, Modifier.testTag("catalog-popover")) {
            HbColumn {
                HbText(hbString(HbString.SeedPrompt), maxLines = 1)
                HbRow {
                    HbText("$percent%")
                    HbIconButton(
                        HbIcons.Minus,
                        hbString(HbString.DiagramZoomOut),
                        { percent -= 25 },
                        enabled = percent > 25,
                    )
                    HbIconButton(
                        HbIcons.Plus,
                        hbString(HbString.DiagramZoomIn),
                        { percent += 25 },
                        enabled = percent < 200,
                    )
                    HbButton(hbString(HbString.Reset), { percent = 100 })
                }
            }
        }
    }
}
