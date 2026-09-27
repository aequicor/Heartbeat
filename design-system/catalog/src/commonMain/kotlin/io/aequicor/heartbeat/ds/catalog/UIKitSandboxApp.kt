package io.aequicor.heartbeat.ds.catalog

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.heartbeat.ds.adaptive.PlatformUi
import io.aequicor.heartbeat.ds.adaptive.supportedPlatformUis
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbDivider
import io.aequicor.heartbeat.ds.components.HbGlassScene
import io.aequicor.heartbeat.ds.components.HbIcon
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbPanel
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.layouts.HbAdaptivePane
import io.aequicor.heartbeat.ds.layouts.HbBoxWithConstraints
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.layouts.hbHorizontalScroll
import io.aequicor.heartbeat.ds.layouts.hbVerticalScroll
import io.aequicor.heartbeat.ds.resources.HbLocale
import io.aequicor.heartbeat.ds.resources.HbResources
import io.aequicor.heartbeat.ds.resources.HbString
import io.aequicor.heartbeat.ds.resources.hbString
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.theme.HbVisualStyle

/** Shared, offline UIKit catalog. Platform applications own the window, lifecycle and logging setup. */
@Composable
public fun UIKitSandboxApp(
    modifier: Modifier = Modifier,
    initialPlatformUi: PlatformUi = PlatformUi.Material,
    initialDarkTheme: Boolean? = false,
    initialVisualStyle: HbVisualStyle = HbVisualStyle.Glass,
) {
    val systemLanguage = Locale.current.language
    val state = remember {
        SandboxState(
            platform = initialPlatformUi,
            language = if (systemLanguage == "ru") HbLocale.Russian else HbLocale.English,
            darkTheme = initialDarkTheme,
            style = initialVisualStyle,
        )
    }
    val isDark = when (state.theme) {
        SandboxTheme.System -> isSystemInDarkTheme()
        SandboxTheme.Light -> false
        SandboxTheme.Dark -> true
    }
    HbResources(locale = state.locale) {
        val copy = chatDemoCopy()
        val scope = rememberCoroutineScope()
        val chat = remember(scope) { DemoChatState(scope, copy) }
        HbTheme(darkTheme = isDark, platformUi = state.platformUi, visualStyle = state.visualStyle) {
            HbGlassScene(modifier = modifier.fillMaxSize()) {
                HbBoxWithConstraints(
                    modifier = Modifier.fillMaxSize()
                        .safeDrawingPadding().imePadding(),
                ) {
                    val isNarrow = maxWidth - HbTheme.spacing.m * 2 < HbTheme.dimensions.compactBreakpoint
                    val isCompact = isNarrow ||
                        maxHeight < HbTheme.dimensions.compactHeightBreakpoint
                    HbColumn(modifier = Modifier.fillMaxSize(), gap = HbTheme.elevation.none) {
                        HbColumn(
                            modifier = Modifier.fillMaxWidth().background(HbTheme.colors.glassTint)
                                .testTag("sandbox-header"),
                            gap = HbTheme.elevation.none,
                        ) {
                            SandboxHeader(state = state, isCompact = isCompact, isNarrow = isNarrow)
                            HbDivider()
                        }
                        HbAdaptivePane(
                            modifier = Modifier.weight(1f).fillMaxWidth().padding(HbTheme.spacing.m)
                                .testTag("sandbox-body"),
                            compactSidebar = { CompactNavigation(state = state) },
                            sidebar = { SandboxSidebar(state = state) },
                        ) {
                            CatalogContent(
                                state = state,
                                chat = chat,
                                copy = copy,
                                isCompact = isCompact,
                                modifier = Modifier.widthIn(max = HbTheme.dimensions.contentMaxWidth).fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SandboxHeader(state: SandboxState, isCompact: Boolean, isNarrow: Boolean, modifier: Modifier = Modifier) {
    HbRow(
        modifier = modifier.fillMaxWidth().padding(
            horizontal = if (isCompact) HbTheme.spacing.m else HbTheme.spacing.l,
            vertical = HbTheme.spacing.s,
        ),
        gap = HbTheme.spacing.s,
    ) {
        SandboxBrand(modifier = Modifier.weight(1f))
        ThemeControl(state = state)
        LanguageControls(state = state, isCompact = isCompact)
        VisualStyleControl(state = state)
        if (!isNarrow && state.visualStyle == HbVisualStyle.Platform) PlatformControl(state = state)
    }
}

@Composable
private fun SandboxBrand(modifier: Modifier = Modifier) {
    HbText(
        hbString(HbString.AppName),
        modifier = modifier,
        style = HbTheme.typography.label,
        maxLines = 1,
    )
}

@Composable
private fun ThemeControl(state: SandboxState, modifier: Modifier = Modifier) {
    HbButton(
        text = hbString(state.theme.title),
        onClick = {
            val next = SandboxTheme.entries[(state.theme.ordinal + 1) % SandboxTheme.entries.size]
            state.selectTheme(next)
        },
        modifier = modifier,
        style = HbButtonStyle.Quiet,
    )
}

@Composable
private fun LanguageControls(state: SandboxState, isCompact: Boolean, modifier: Modifier = Modifier) {
    HbRow(modifier = modifier, gap = HbTheme.spacing.xxs) {
        if (isCompact) {
            val nextLocale = if (state.locale == HbLocale.English) HbLocale.Russian else HbLocale.English
            HbButton(
                text = hbString(if (nextLocale == HbLocale.English) HbString.English else HbString.Russian),
                onClick = { state.selectLocale(nextLocale) },
                style = HbButtonStyle.Quiet,
            )
        } else {
            LanguageButton(state = state, locale = HbLocale.English, title = HbString.English)
            LanguageButton(state = state, locale = HbLocale.Russian, title = HbString.Russian)
        }
    }
}

@Composable
private fun LanguageButton(state: SandboxState, locale: HbLocale, title: HbString, modifier: Modifier = Modifier) {
    HbButton(
        text = hbString(title),
        onClick = { state.selectLocale(locale) },
        modifier = modifier.semantics { selected = state.locale == locale },
        style = HbButtonStyle.Quiet,
    )
}

@Composable
private fun VisualStyleControl(state: SandboxState, modifier: Modifier = Modifier) {
    val isSoft = state.visualStyle != HbVisualStyle.Platform
    HbButton(
        text = hbString(if (isSoft) HbString.SoftUi else HbString.NativeUi),
        onClick = {
            state.selectVisualStyle(if (isSoft) HbVisualStyle.Platform else HbVisualStyle.Glass)
        },
        modifier = modifier,
        style = HbButtonStyle.Quiet,
    )
}

@Composable
private fun PlatformControl(state: SandboxState, modifier: Modifier = Modifier) {
    val platforms = supportedPlatformUis()
    HbButton(
        text = hbString(state.platformUi.title()),
        onClick = {
            val next = platforms[(platforms.indexOf(state.platformUi) + 1) % platforms.size]
            state.selectPlatform(next)
        },
        modifier = modifier,
        style = HbButtonStyle.Quiet,
    )
}

@Composable
private fun SandboxSidebar(state: SandboxState, modifier: Modifier = Modifier) {
    HbPanel(modifier = modifier.fillMaxSize().testTag("sandbox-sidebar")) {
        HbColumn(
            modifier = Modifier.fillMaxSize().hbVerticalScroll(rememberScrollState()).padding(HbTheme.spacing.m),
            gap = HbTheme.spacing.xxs,
        ) {
            SandboxPage.entries.forEach { page ->
                NavigationItem(page = page, state = state, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun NavigationItem(page: SandboxPage, state: SandboxState, modifier: Modifier = Modifier) {
    val isSelected = state.page == page
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    val colors = HbTheme.colors
    val background = navigationBackground(isSelected, isHovered, isPressed)
    HbRow(
        modifier = modifier.heightIn(min = HbTheme.dimensions.touchTarget)
            .clickable(interactionSource = interactionSource, indication = null, role = Role.Tab) {
                state.selectPage(page)
            }
            .semantics { selected = isSelected }
            .background(background, HbTheme.shapes.small)
            .border(
                HbTheme.dimensions.borderWidth,
                colors.outline.copy(alpha = if (isFocused) 1f else 0f),
                HbTheme.shapes.small,
            )
            .padding(horizontal = HbTheme.spacing.m, vertical = HbTheme.spacing.s),
        gap = HbTheme.spacing.s,
    ) {
        HbIcon(
            icon = page.icon(isSelected),
            contentDescription = null,
            tint = if (isSelected) colors.textPrimary else colors.textSecondary,
        )
        HbText(
            hbString(page.title),
            style = HbTheme.typography.label,
            color = if (isSelected) HbTheme.colors.textPrimary else HbTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun navigationBackground(isSelected: Boolean, isHovered: Boolean, isPressed: Boolean): Color {
    val colors = HbTheme.colors
    val base = if (isSelected) colors.primaryContainer else Color.Transparent
    val target = when {
        isPressed -> colors.pressedOverlay.compositeOver(base)
        isHovered -> colors.interactionHoverOverlay.compositeOver(base)
        else -> base
    }
    return key(colors, isSelected) {
        val background by animateColorAsState(
            targetValue = target,
            animationSpec = if (HbTheme.motion.isReducedMotion) snap() else tween(HbTheme.motion.fastMillis),
            label = "navigationBackground",
        )
        background
    }
}

@Composable
private fun CompactNavigation(state: SandboxState, modifier: Modifier = Modifier) {
    val pages = remember {
        listOf(SandboxPage.Chat, SandboxPage.Foundation, SandboxPage.Components, SandboxPage.Layouts)
    }
    HbPanel(modifier = modifier.fillMaxWidth().testTag("sandbox-compact-navigation")) {
        HbRow(
            modifier = Modifier.fillMaxWidth().hbHorizontalScroll(rememberScrollState())
                .padding(horizontal = HbTheme.spacing.m, vertical = HbTheme.spacing.xs),
            gap = HbTheme.spacing.xs,
        ) {
            pages.forEach { page -> NavigationItem(page = page, state = state) }
            if (state.visualStyle == HbVisualStyle.Platform) PlatformControl(state = state)
        }
    }
}

@Composable
private fun CatalogContent(
    state: SandboxState,
    chat: DemoChatState,
    copy: ChatDemoCopy,
    isCompact: Boolean,
    modifier: Modifier = Modifier,
) {
    when (state.page) {
        SandboxPage.Foundation -> FoundationCatalog(modifier = modifier)
        SandboxPage.Components -> ComponentsCatalog(state = state, modifier = modifier)
        SandboxPage.Layouts -> LayoutsCatalog(modifier = modifier)
        SandboxPage.Chat -> ChatCatalog(state = chat, copy = copy, isCompact = isCompact, modifier = modifier)
    }
}

@Composable
internal fun ChoiceButton(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    HbButton(
        text = text,
        onClick = onClick,
        modifier = modifier.semantics { this.selected = selected },
        style = if (selected) HbButtonStyle.Primary else HbButtonStyle.Quiet,
    )
}

@Composable
internal fun CatalogHeading(title: HbString, description: HbString, modifier: Modifier = Modifier) {
    HbColumn(modifier = modifier, gap = HbTheme.spacing.m) {
        HbText(hbString(title), style = HbTheme.typography.title)
        HbText(hbString(description), color = HbTheme.colors.textSecondary)
    }
}

private fun PlatformUi.title(): HbString = when (this) {
    PlatformUi.Material -> HbString.Material
    PlatformUi.Fluent -> HbString.Fluent
    PlatformUi.MacOs -> HbString.MacOs
}

private fun SandboxPage.icon(isSelected: Boolean): ImageVector = when (this) {
    SandboxPage.Foundation -> if (isSelected) HbIcons.HomeFilled else HbIcons.Home
    SandboxPage.Components -> HbIcons.Library
    SandboxPage.Layouts -> HbIcons.Sidebar
    SandboxPage.Chat -> HbIcons.Chat
}

@Composable
private fun chatDemoCopy(): ChatDemoCopy = ChatDemoCopy(
    user = hbString(HbString.You),
    agent = hbString(HbString.Agent),
    tool = hbString(HbString.Tool),
    studio = hbString(HbString.SystemAuthor),
    prompt = hbString(HbString.SeedPrompt),
    reply = hbString(HbString.SeedReply),
    toolResult = hbString(HbString.ToolResult),
    notice = hbString(HbString.NoticeText),
    code = hbString(HbString.CodeSample),
    codeLabel = hbString(HbString.CodeLabel),
    response = hbString(HbString.StreamResponse),
    section = hbString(HbString.CurrentSection),
    historySection = hbString(HbString.HistorySection),
)

@Preview
@Composable
private fun UIKitSandboxLightPreview() {
    UIKitSandboxApp(initialDarkTheme = false)
}

@Preview
@Composable
private fun UIKitSandboxDarkPreview() {
    UIKitSandboxApp(initialDarkTheme = true)
}
