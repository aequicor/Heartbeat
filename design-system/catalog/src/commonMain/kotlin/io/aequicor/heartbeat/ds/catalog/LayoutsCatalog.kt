package io.aequicor.heartbeat.ds.catalog

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.aequicor.heartbeat.ds.components.HbBadge
import io.aequicor.heartbeat.ds.components.HbCard
import io.aequicor.heartbeat.ds.components.HbGlassPanel
import io.aequicor.heartbeat.ds.components.HbStickyHeaderHost
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbTone
import io.aequicor.heartbeat.ds.components.hbSurface
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbFlowRow
import io.aequicor.heartbeat.ds.layouts.HbLazyColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.layouts.hbStickyHeader
import io.aequicor.heartbeat.ds.resources.HbString
import io.aequicor.heartbeat.ds.resources.hbString
import io.aequicor.heartbeat.ds.theme.HbTheme

@Composable
internal fun LayoutsCatalog(modifier: Modifier = Modifier) {
    val stickyState = rememberLazyListState()
    HbLazyColumn(modifier = modifier) {
        item { CatalogHeading(HbString.LayoutsTitle, HbString.LayoutsDescription) }
        item {
            HbCard(modifier = Modifier.fillMaxWidth()) {
                HbText(hbString(HbString.RowTitle), style = HbTheme.typography.title)
                HbFlowRow {
                    HbBadge(hbString(HbString.LayoutItemOne), tone = HbTone.Brand)
                    HbBadge(hbString(HbString.LayoutItemTwo), tone = HbTone.Success)
                    HbBadge(hbString(HbString.LayoutItemThree), tone = HbTone.Warning)
                }
            }
        }
        item {
            HbCard(modifier = Modifier.fillMaxWidth()) {
                HbText(hbString(HbString.ColumnTitle), style = HbTheme.typography.title)
                HbColumn {
                    HbBadge(hbString(HbString.LayoutItemOne), tone = HbTone.Brand)
                    HbBadge(hbString(HbString.LayoutItemTwo), tone = HbTone.Success)
                    HbBadge(hbString(HbString.LayoutItemThree), tone = HbTone.Warning)
                }
            }
        }
        item {
            HbCard(modifier = Modifier.fillMaxWidth()) {
                HbText(hbString(HbString.LazyTitle), style = HbTheme.typography.title)
                HbStickyHeaderHost(
                    state = stickyState,
                    stickyHeaderKeyPrefix = "layout-section-",
                    modifier = Modifier.fillMaxWidth().height(HbTheme.dimensions.sidebarWidth),
                    header = { key, headerModifier ->
                        StickyLayoutHeading(key.removePrefix("layout-section-").toInt() + 1, headerModifier)
                    },
                ) { headerContent ->
                    HbLazyColumn(modifier = Modifier.fillMaxSize(), state = stickyState, showScrollbar = false) {
                        repeat(DEMO_SECTIONS) { section ->
                            hbStickyHeader(key = "layout-section-$section") {
                                headerContent("layout-section-$section")
                            }
                            items(
                                count = DEMO_SECTION_ITEMS,
                                key = { "layout-row-$section-$it" },
                                contentType = { "layout-row" },
                            ) { index ->
                                HbRow(modifier = Modifier.fillMaxWidth()) {
                                    HbBadge((section * DEMO_SECTION_ITEMS + index + 1).toString(), tone = HbTone.Brand)
                                    HbText(hbString(HbString.LayoutItemOne))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StickyLayoutHeading(sectionNumber: Int, modifier: Modifier = Modifier) {
    val shape = HbTheme.shapes.medium
    HbGlassPanel(modifier = modifier, shape = shape) {
        HbText(
            text = "${hbString(HbString.Conversation)} $sectionNumber",
            modifier = Modifier
                .fillMaxWidth()
                .hbSurface(HbTheme.colors.surfaceElevated, shape)
                .padding(HbTheme.spacing.l)
                .semantics { heading() },
            style = HbTheme.typography.label,
        )
    }
}

private const val DEMO_SECTIONS = 3
private const val DEMO_SECTION_ITEMS = 10
