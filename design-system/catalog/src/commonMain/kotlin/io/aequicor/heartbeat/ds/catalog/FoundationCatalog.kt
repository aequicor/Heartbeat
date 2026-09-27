package io.aequicor.heartbeat.ds.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.aequicor.heartbeat.ds.components.HbCard
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.hbSurface
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbFlowRow
import io.aequicor.heartbeat.ds.layouts.HbLazyColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.resources.HbString
import io.aequicor.heartbeat.ds.resources.hbString
import io.aequicor.heartbeat.ds.theme.HbTheme

@Composable
internal fun FoundationCatalog(modifier: Modifier = Modifier) {
    HbLazyColumn(modifier = modifier) {
        item {
            HbColumn(
                modifier = Modifier.fillMaxWidth()
                    .hbSurface(HbTheme.colors.primary, HbTheme.shapes.large)
                    .padding(HbTheme.spacing.xl),
            ) {
                HbText(
                    hbString(HbString.MissionTitle),
                    style = HbTheme.typography.display,
                    color = HbTheme.colors.onPrimary,
                )
                HbText(hbString(HbString.MissionDescription), color = HbTheme.colors.onPrimary)
            }
        }
        item {
            HbColumn {
                CatalogHeading(HbString.Palette, HbString.PaletteDescription)
                HbFlowRow {
                    val colors = HbTheme.colors
                    ColorSwatch(HbString.Brand, colors.brand)
                    ColorSwatch(HbString.Primary, colors.primary)
                    ColorSwatch(HbString.Secondary, colors.secondary)
                    ColorSwatch(HbString.Success, colors.success)
                    ColorSwatch(HbString.Warning, colors.warning)
                    ColorSwatch(HbString.Danger, colors.error)
                    ColorSwatch(HbString.Violet, colors.dataViolet)
                    ColorSwatch(HbString.Cyan, colors.dataCyan)
                }
            }
        }
        item {
            HbCard(modifier = Modifier.fillMaxWidth()) {
                HbText(hbString(HbString.Typography), style = HbTheme.typography.label)
                HbText(hbString(HbString.DisplaySample), style = HbTheme.typography.display)
                HbText(hbString(HbString.TitleSample), style = HbTheme.typography.title)
                HbText(hbString(HbString.BodySample))
                HbText(
                    hbString(HbString.CaptionSample),
                    style = HbTheme.typography.caption,
                    color = HbTheme.colors.textSecondary,
                )
            }
        }
        item {
            HbCard(modifier = Modifier.fillMaxWidth()) {
                CatalogHeading(HbString.Spacing, HbString.SpacingDescription)
                val spacing = HbTheme.spacing
                HbFlowRow {
                    listOf(spacing.xs, spacing.s, spacing.m, spacing.l, spacing.xl, spacing.xxl).forEach { step ->
                        HbRow(gap = spacing.s) {
                            HbColumn(
                                modifier = Modifier.width(step).height(spacing.xl)
                                    .background(HbTheme.colors.brand, HbTheme.shapes.small),
                            ) {}
                            HbText(step.value.toInt().toString(), style = HbTheme.typography.code)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColorSwatch(title: HbString, color: Color, modifier: Modifier = Modifier) {
    HbColumn(modifier = modifier.width(HbTheme.dimensions.sidebarWidth / 2), gap = HbTheme.spacing.s) {
        HbColumn(
            modifier = Modifier.size(HbTheme.dimensions.swatchSize).hbSurface(color, HbTheme.shapes.medium),
        ) {}
        HbText(hbString(title), style = HbTheme.typography.caption)
    }
}
