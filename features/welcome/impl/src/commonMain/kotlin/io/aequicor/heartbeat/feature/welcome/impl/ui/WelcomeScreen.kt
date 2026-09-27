package io.aequicor.heartbeat.feature.welcome.impl.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbCinematicBackdrop
import io.aequicor.heartbeat.ds.components.HbGlassOrbit
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.layouts.HbBoxWithConstraints
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.layouts.hbVerticalScroll
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.welcome.impl.presentation.store.WelcomeModel
import io.aequicor.heartbeat.feature.welcome.impl.presentation.store.WelcomePhase
import io.aequicor.heartbeat.feature.welcome.impl.presentation.store.WelcomeScreenIntent
import io.aequicor.heartbeat.feature.welcome.impl.presentation.store.WelcomeScreenState
import io.aequicor.heartbeat.feature.welcome.impl.resources.Res
import io.aequicor.heartbeat.feature.welcome.impl.resources.welcome_brand
import io.aequicor.heartbeat.feature.welcome.impl.resources.welcome_footer
import io.aequicor.heartbeat.feature.welcome.impl.resources.welcome_skip
import io.aequicor.heartbeat.feature.welcome.impl.resources.welcome_studio
import io.aequicor.heartbeat.feature.welcome.impl.resources.welcome_subtitle
import io.aequicor.heartbeat.feature.welcome.impl.resources.welcome_title
import io.aequicor.heartbeat.feature.welcome.impl.resources.welcome_toggles
import org.jetbrains.compose.resources.stringResource
import pro.respawn.flowmvi.dsl.collect

@Composable
internal fun WelcomeScreen(model: WelcomeModel, modifier: Modifier = Modifier) {
    val state by produceState(WelcomeScreenState(), model) {
        model.store.collect { states.collect { value = it } }
    }
    WelcomeContent(state.phase, model.store::intent, modifier)
}

@Composable
internal fun WelcomeContent(
    phase: WelcomePhase,
    onIntent: (WelcomeScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbTheme(darkTheme = false, motion = HbTheme.motion) {
        val progress = rememberIntroProgress(phase, onIntent)
        val isIntro = phase == WelcomePhase.Intro || phase == WelcomePhase.Preparing
        val isReady = phase == WelcomePhase.Ready
        val skipFocus = remember { FocusRequester() }
        val studioFocus = remember { FocusRequester() }
        LaunchedEffect(isIntro, isReady) {
            // Wait until the newly revealed controls are attached and laid out.
            withFrameNanos { }
            if (isIntro) skipFocus.requestFocus()
            if (isReady) studioFocus.requestFocus()
        }
        val sceneProgress = { if (isIntro) progress.value else 1f }
        Box(
            modifier.fillMaxSize().testTag("welcome").skipIntroOnEscape(isIntro, onIntent),
        ) {
            HbCinematicBackdrop(sceneProgress)
            WelcomeStage(sceneProgress, isReady, onIntent, studioFocus)
            if (isIntro) {
                HbButton(
                    stringResource(Res.string.welcome_skip),
                    { onIntent(WelcomeScreenIntent.Skip) },
                    Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(HbTheme.spacing.l)
                        .focusRequester(skipFocus).testTag("welcome-skip"),
                    style = HbButtonStyle.Quiet,
                )
            }
        }
    }
}

private fun Modifier.skipIntroOnEscape(isIntro: Boolean, onIntent: (WelcomeScreenIntent) -> Unit): Modifier =
    onPreviewKeyEvent {
        if (isIntro && it.key == Key.Escape && it.type == KeyEventType.KeyUp) {
            onIntent(WelcomeScreenIntent.Skip)
            true
        } else {
            false
        }
    }

@Composable
private fun rememberIntroProgress(phase: WelcomePhase, onIntent: (WelcomeScreenIntent) -> Unit): State<Float> {
    val progress = rememberSaveable { mutableFloatStateOf(0f) }
    val event by rememberUpdatedState(onIntent)
    val isReducedMotion = HbTheme.motion.isReducedMotion
    val durationMillis = HbTheme.welcome.durationMillis
    LaunchedEffect(phase, isReducedMotion) {
        if (isReducedMotion || coroutineContext[MotionDurationScale]?.scaleFactor == 0f) {
            progress.floatValue = 1f
            if (phase == WelcomePhase.Intro || phase == WelcomePhase.Preparing) event(WelcomeScreenIntent.Skip)
        } else if (phase == WelcomePhase.Intro) {
            var previous = withFrameNanos { it }
            while (progress.floatValue < 1f) {
                val now = withFrameNanos { it }
                if (coroutineContext[MotionDurationScale]?.scaleFactor == 0f) {
                    progress.floatValue = 1f
                    break
                }
                progress.floatValue = (progress.floatValue + (now - previous) / (durationMillis * NANOS_PER_MILLI))
                    .coerceAtMost(1f)
                previous = now
            }
            event(WelcomeScreenIntent.IntroFinished)
        }
    }
    return progress
}

@Composable
private fun WelcomeStage(
    progress: () -> Float,
    isReady: Boolean,
    onIntent: (WelcomeScreenIntent) -> Unit,
    studioFocus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val tokens = HbTheme.welcome
    HbBoxWithConstraints(modifier.fillMaxSize().safeDrawingPadding()) {
        val isCompact = maxWidth < HbTheme.dimensions.compactBreakpoint
        val viewportHeight = maxHeight
        val pagePadding = if (isCompact) tokens.compactPagePadding else tokens.pagePadding
        HbColumn(Modifier.fillMaxSize().hbVerticalScroll(rememberScrollState())) {
            Box(
                Modifier.fillMaxWidth().heightIn(min = viewportHeight)
                    .padding(pagePadding),
                contentAlignment = Alignment.Center,
            ) {
                HbColumn(
                    Modifier.widthIn(max = tokens.maxWidth).fillMaxWidth(),
                    gap = tokens.sectionGap,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    HbText(stringResource(Res.string.welcome_brand), style = tokens.brand)
                    HbGlassOrbit(
                        progress,
                        Modifier.size(if (isCompact) tokens.compactSymbolSize else tokens.symbolSize),
                    )
                    WelcomeTitle(isCompact, progress)
                    WelcomeActions(
                        isCompact,
                        isReady,
                        onIntent,
                        studioFocus,
                        Modifier.graphicsLayer {
                            alpha = ((progress() - tokens.actionsStart) / (1f - tokens.actionsStart)).coerceIn(0f, 1f)
                        }.then(if (isReady) Modifier else Modifier.clearAndSetSemantics { }),
                    )
                    HbText(
                        stringResource(Res.string.welcome_footer),
                        style = HbTheme.typography.caption.copy(textAlign = TextAlign.Center),
                        color = HbTheme.colors.textSecondary,
                        modifier = Modifier.graphicsLayer { alpha = progress() },
                    )
                }
            }
        }
    }
}

@Composable
private fun WelcomeTitle(isCompact: Boolean, progress: () -> Float, modifier: Modifier = Modifier) {
    val tokens = HbTheme.welcome
    HbColumn(
        modifier.graphicsLayer {
            alpha = ((progress() - tokens.titleStart) / (tokens.actionsStart - tokens.titleStart)).coerceIn(0f, 1f)
            translationY = (1f - alpha) * tokens.sectionGap.toPx()
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HbText(stringResource(Res.string.welcome_title), style = if (isCompact) tokens.compactTitle else tokens.title)
        HbText(
            stringResource(Res.string.welcome_subtitle),
            style = HbTheme.typography.body.copy(textAlign = TextAlign.Center),
            color = HbTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun WelcomeActions(
    isCompact: Boolean,
    enabled: Boolean,
    onIntent: (WelcomeScreenIntent) -> Unit,
    studioFocus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val actions: @Composable () -> Unit = {
        HbButton(
            stringResource(Res.string.welcome_studio),
            { onIntent(WelcomeScreenIntent.OpenStudio) },
            Modifier.widthIn(min = HbTheme.welcome.actionWidth).heightIn(min = HbTheme.welcome.actionHeight)
                .focusRequester(studioFocus).testTag("welcome-studio"),
            enabled = enabled,
        )
        HbButton(
            stringResource(Res.string.welcome_toggles),
            { onIntent(WelcomeScreenIntent.OpenToggles) },
            Modifier.widthIn(min = HbTheme.welcome.actionWidth).heightIn(min = HbTheme.welcome.actionHeight)
                .testTag("welcome-toggles"),
            style = HbButtonStyle.Secondary,
            enabled = enabled,
        )
    }
    if (isCompact) {
        HbColumn(modifier, horizontalAlignment = Alignment.CenterHorizontally) { actions() }
    } else {
        HbRow(modifier) { actions() }
    }
}

private const val NANOS_PER_MILLI = 1_000_000f

@Preview
@Composable
private fun WelcomePreview() {
    HbTheme { WelcomeContent(WelcomePhase.Ready, {}) }
}
