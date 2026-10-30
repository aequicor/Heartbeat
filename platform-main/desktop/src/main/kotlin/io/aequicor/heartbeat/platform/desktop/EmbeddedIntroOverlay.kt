// Coordinates and pigments mirror the approved native brand artwork and Win32 COLORREF encoding.
@file:Suppress("MagicNumber")

package io.aequicor.heartbeat.platform.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.sun.jna.platform.win32.Kernel32
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.platform.nativelauncher.LauncherBrand
import io.aequicor.heartbeat.platform.nativelauncher.LauncherPoint

/** Opaque matching frame over the live Studio. Only the native reveal acknowledgement starts its continuation. */
@Composable
internal fun EmbeddedIntroOverlay(
    revealedAt: Long?,
    modifier: Modifier = Modifier,
    onVisibleChange: (Boolean) -> Unit = {},
) {
    var now by remember { mutableLongStateOf(Kernel32.INSTANCE.GetTickCount64()) }
    LaunchedEffect(revealedAt) {
        animateEmbeddedIntro(revealedAt, now, { withFrameNanos { Kernel32.INSTANCE.GetTickCount64() } }) { now = it }
    }
    val frame = EmbeddedIntroTimeline.continuation(now, revealedAt)
    SideEffect { onVisibleChange(frame.opacity > 0) }
    val colors = HbTheme.colors
    val surfaces = HbTheme.surfaces
    val headerHeight = HbTheme.dimensions.headerHeight
    val ink = colors.startupPulseInk
    if (frame.opacity <= 0) return
    Box(
        modifier.fillMaxSize().pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(PointerEventPass.Initial).changes.forEach {
                        if (it.position.y >= headerHeight.toPx()) it.consume()
                    }
                }
            }
        },
    ) {
        Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = frame.opacity.toFloat() }) {
            drawEmbeddedBrand(colors.background, surfaces.sidebar, ink, frame.transform.toFloat())
        }
        HbRow(
            Modifier.width(HbTheme.dimensions.startupTitleWidth).height(HbTheme.dimensions.headerHeight)
                .background(surfaces.sidebar)
                .padding(horizontal = HbTheme.spacing.l),
        ) {
            HbText("Heartbeat", style = HbTheme.typography.label, color = HbTheme.colors.textSecondary, maxLines = 1)
        }
    }
}

internal suspend fun animateEmbeddedIntro(
    revealedAt: Long?,
    initialTime: Long,
    nextFrame: suspend () -> Long,
    onFrame: (Long) -> Unit,
) {
    if (revealedAt == null) return
    var now = initialTime
    while (EmbeddedIntroTimeline.continuation(now, revealedAt).opacity > 0) {
        now = nextFrame()
        onFrame(now)
    }
}

private fun List<LauncherPoint>.path(project: (LauncherPoint) -> Offset): Path = Path().apply {
    forEachIndexed { index, point ->
        val value = project(point)
        if (index == 0) moveTo(value.x, value.y) else lineTo(value.x, value.y)
    }
}

private fun UInt.color(): Color = Color(
    (this and 255u).toInt(),
    ((this shr 8) and 255u).toInt(),
    ((this shr 16) and 255u).toInt(),
)

private fun DrawScope.drawEmbeddedBrand(background: Color, sidebarColor: Color, ink: Color, transform: Float) {
    // Coordinates are the approved native brand artwork; surfaces come from the host's design tokens.
    drawRect(background)
    val sidebar = if (size.width / density >= 720) 264 * density else 0f
    drawRect(sidebarColor, size = Size(sidebar, size.height))
    val cx = (sidebar + (size.width - sidebar) / 2) / density
    val cy = size.height / density / 2
    val shell = LauncherBrand.shell.path { point ->
        Offset(
            (cx - 60 + point.x.toFloat() * 120 / 128) * density,
            (cy - 120 + point.y.toFloat() * 120 / 128) * density,
        )
    }.apply { close() }
    drawPath(
        shell,
        Brush.verticalGradient(
            listOf(LauncherBrand.BLUE.color(), LauncherBrand.VIOLET.color()),
            (cy - 120) * density,
            cy * density,
        ),
        alpha = 1 - transform,
    )
    val pulse = LauncherBrand.pulse.path { point ->
        val from = Offset(cx - 60 + point.x.toFloat() * 120 / 128, cy - 120 + point.y.toFloat() * 120 / 128)
        val to = Offset(
            cx - 160 + (point.x.toFloat() - 26) * 320 / 77,
            cy + 12 + (point.y.toFloat() - 69) * .75f,
        )
        (from + (to - from) * transform) * density
    }
    drawPath(
        pulse,
        androidx.compose.ui.graphics.lerp(LauncherBrand.WHITE.color(), ink, transform),
        style = Stroke((4 - transform) * density, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}
