package io.aequicor.heartbeat.ds.catalog

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbCinematicBackdrop
import io.aequicor.heartbeat.ds.components.HbGlassOrbit
import io.aequicor.heartbeat.ds.components.HbSwitch
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.resources.HbString
import io.aequicor.heartbeat.ds.resources.hbString
import io.aequicor.heartbeat.ds.theme.HbTheme

@Composable
internal fun CinematicExample(modifier: Modifier = Modifier) {
    val progress = remember { Animatable(1f) }
    var replay by remember { mutableIntStateOf(0) }
    var isEnabled by remember { mutableStateOf(true) }
    val tokens = HbTheme.welcome
    val isReduced = HbTheme.motion.isReducedMotion
    LaunchedEffect(replay, isReduced, isEnabled) {
        if (replay > 0 && !isReduced && isEnabled) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(tokens.durationMillis, easing = LinearEasing))
        } else {
            progress.snapTo(1f)
        }
    }
    HbColumn(modifier) {
        HbText(hbString(HbString.CinematicWelcome), style = HbTheme.typography.title)
        Box(Modifier.fillMaxWidth().height(tokens.symbolSize), contentAlignment = Alignment.Center) {
            HbCinematicBackdrop({ progress.value })
            HbGlassOrbit({ progress.value }, Modifier.size(tokens.symbolSize))
        }
        HbRow {
            HbButton(hbString(HbString.ReplayIntro), { replay++ })
            HbSwitch(isEnabled, { isEnabled = it }, hbString(HbString.CinematicWelcome))
        }
    }
}
