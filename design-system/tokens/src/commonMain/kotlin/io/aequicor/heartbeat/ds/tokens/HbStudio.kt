package io.aequicor.heartbeat.ds.tokens

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Pearl conversation surfaces for the studio's blue-white and lavender visual language.
 * [rail], [sidebar] and [header] are translucent panels over [backdrop] and its ambient light fields.
 * [outgoing] distinguishes user messages; [selected] and [onSelected] identify the active conversation.
 * [assistant] is always opaque so the backdrop never competes with an agent's continuous answer.
 * [tool] and [console] are subordinate surfaces inside that answer; [outline] separates their edges.
 * [accent] and [success] are readable foregrounds, not pastel fills. Composer roles distinguish its
 * glass surface, quiet and lavender pills, and the high-contrast circular send action.
 * [avatar] fills conversation identity circles, while the ambient roles supply broad decorative light.
 */
@Immutable
data class HbStudioColors(
    val backdrop: Color,
    val rail: Color,
    val sidebar: Color,
    val header: Color,
    val outgoing: Color,
    val selected: Color,
    val onSelected: Color,
    val avatar: Color,
    val ambientLavender: Color,
    val ambientBlue: Color,
    val ambientPeach: Color,
    val assistant: Color,
    val composer: Color,
    val tool: Color,
    val console: Color,
    val outline: Color,
    val accent: Color,
    val success: Color,
    val composerAction: Color,
    val onComposerAction: Color,
    val composerPill: Color,
    val composerPillAccent: Color,
    val onComposerPillAccent: Color,
) {
    /** Matching light and dark surfaces; ordinary content uses the scoped [HbStudioStyle] palette. */
    companion object {
        val Light = HbStudioColors(
            backdrop = HbStudioStyle.lightColors.background,
            rail = Color(0xC2F4F9FF),
            sidebar = Color(0xB8F8FBFF),
            header = Color(0xC2FFFFFF),
            outgoing = Color(0xFFE2DCFF),
            selected = Color(0xFFE6E0FC),
            onSelected = HbStudioStyle.lightColors.textPrimary,
            avatar = Color(0xFFF7F5FF),
            ambientLavender = Color(0x47C9C0FD),
            ambientBlue = Color(0x4DA7D9EC),
            ambientPeach = Color(0x0AF0E5EF),
            assistant = Color.White,
            composer = Color(0xE8FFFFFF),
            tool = Color(0xFFF6F8FC),
            console = Color(0xFFEEF2F8),
            outline = Color(0xFFDCE4EF),
            accent = Color(0xFF6650D1),
            success = Color(0xFF13783D),
            composerAction = Color(0xFF30364C),
            onComposerAction = Color.White,
            composerPill = Color(0xD6FFFFFF),
            composerPillAccent = Color(0xFFECE6FF),
            onComposerPillAccent = Color(0xFF583ACC),
        )
        val Dark = HbStudioColors(
            backdrop = HbStudioStyle.darkColors.background,
            rail = Color(0xD1263244),
            sidebar = Color(0xD1293547),
            header = Color(0xE02B3648),
            outgoing = Color(0xFF46405E),
            selected = Color(0xFF433C5D),
            onSelected = HbStudioStyle.darkColors.textPrimary,
            avatar = Color(0xFF39364F),
            ambientLavender = Color(0x1AC1ABF7),
            ambientBlue = Color(0x1A84BCD5),
            ambientPeach = Color(0x05E5C7DC),
            assistant = Color(0xFF293344),
            composer = Color(0xF02C3749),
            tool = Color(0xFF303C50),
            console = Color(0xFF243042),
            outline = Color(0xFF44526A),
            accent = Color(0xFFC7B8FF),
            success = Color(0xFF93DEB1),
            composerAction = Color(0xFFDAD4F3),
            onComposerAction = Color(0xFF222A3B),
            composerPill = Color(0xFF354156),
            composerPillAccent = Color(0xFF493E66),
            onComposerPillAccent = Color(0xFFE3D8FF),
        )

        /** Opaque, neutral desktop surfaces; ambient decorations remain a mobile presentation choice. */
        val DesktopLight = Light.copy(
            backdrop = HbStudioStyle.desktopLightColors.background,
            rail = Color(0xFFF4F4F5),
            sidebar = Color(0xFFF4F4F5),
            header = Color.White,
            outgoing = Color(0xFFF1EEF6),
            selected = HbStudioStyle.desktopLightColors.selectedContainer,
            onSelected = HbStudioStyle.desktopLightColors.textPrimary,
            avatar = Color(0xFFEFEAF4),
            ambientLavender = Color.Transparent,
            ambientBlue = Color.Transparent,
            ambientPeach = Color.Transparent,
            assistant = Color.White,
            composer = Color.White,
            tool = Color(0xFFF7F7F8),
            console = Color(0xFFF4F4F5),
            outline = Color(0xFFE3E3E5),
            accent = Color(0xFF6A587C),
            success = Color(0xFF34754A),
            composerAction = Color(0xFF353238),
            onComposerAction = Color.White,
            composerPill = Color(0xFFF2F2F3),
            composerPillAccent = Color(0xFFEEEAF4),
            onComposerPillAccent = Color(0xFF655278),
        )
        val DesktopDark = Dark.copy(
            backdrop = HbStudioStyle.desktopDarkColors.background,
            rail = Color(0xFF28282B),
            sidebar = Color(0xFF28282B),
            header = Color(0xFF202022),
            outgoing = Color(0xFF35313B),
            selected = HbStudioStyle.desktopDarkColors.selectedContainer,
            onSelected = HbStudioStyle.desktopDarkColors.textPrimary,
            avatar = Color(0xFF3A3442),
            ambientLavender = Color.Transparent,
            ambientBlue = Color.Transparent,
            ambientPeach = Color.Transparent,
            assistant = Color(0xFF202022),
            composer = Color(0xFF2A2A2D),
            tool = Color(0xFF29292C),
            console = Color(0xFF242426),
            outline = Color(0xFF424246),
            accent = Color(0xFFC5B8DC),
            success = Color(0xFF8FC69D),
            composerAction = Color(0xFFDCD4E8),
            onComposerAction = Color(0xFF29252F),
            composerPill = Color(0xFF353538),
            composerPillAccent = Color(0xFF3E3748),
            onComposerPillAccent = Color(0xFFD9CBEA),
        )
    }
}

/** Conversation-first studio proportions, independent of the compact general-purpose shell. */
@Immutable
data class HbStudioDimensions(
    val railWidth: Dp = 72.dp,
    val sidebarWidth: Dp = 240.dp,
    val avatarSize: Dp = 44.dp,
    val headerAvatarSize: Dp = 36.dp,
    val cornerRadius: Dp = 24.dp,
    val outerInset: Dp = 32.dp,
    val verticalInset: Dp = 12.dp,
    val panelGap: Dp = 12.dp,
    val headerHeight: Dp = 64.dp,
    val messageMaxWidth: Dp = 1000.dp,
    val composerMaxWidth: Dp = 1090.dp,
    val navigationHeaderTopInset: Dp = 48.dp,
    val sidebarHeadingHeight: Dp = 32.dp,
    val messagePadding: Dp = 24.dp,
    /** Minimum inner answer width before prose shares the author's inset beside the avatar. */
    val messageBodyIndentMinWidth: Dp = 560.dp,
    val messageCornerRadius: Dp = 20.dp,
    val toolPadding: Dp = 12.dp,
    val composerActionSize: Dp = 56.dp,
    val composerPillHeight: Dp = 44.dp,
    val composerMinEditorWidth: Dp = 160.dp,
    /** Largest toolbar label before ellipsis; the full label remains available in a tooltip. */
    val composerLabelMaxWidth: Dp = 160.dp,
    /** Horizontal exclusion zone for native traffic lights when content reaches the window edge. */
    val titlebarLeadingInset: Dp = 96.dp,
    val composerInlineBreakpoint: Dp = 720.dp,
    val markStroke: Dp = 2.dp,
    /** Actual host density, independent of which native control kit is previewed. */
    val isDesktop: Boolean = false,
    val navigationRowHeight: Dp = 48.dp,
    /** Vertical space reserved above sidebar contents for native macOS window controls. */
    val titlebarInset: Dp = 48.dp,
    /** Eight desktop body lines at the standard font scale; mobile keeps its existing input cap. */
    val editorMaxHeight: Dp = 120.dp,
    /** Optical centering above the composer; zero retains the mobile content midpoint. */
    val emptyStateVerticalBias: Float = 0f,
) {
    /** Host-specific presets; compact desktop geometry never reduces mobile touch targets. */
    companion object {
        val Mobile = HbStudioDimensions()
        val Desktop = HbStudioDimensions(
            railWidth = 52.dp,
            sidebarWidth = 264.dp,
            avatarSize = 28.dp,
            headerAvatarSize = 28.dp,
            cornerRadius = 12.dp,
            outerInset = 0.dp,
            verticalInset = 0.dp,
            panelGap = 0.dp,
            headerHeight = 44.dp,
            messageMaxWidth = 760.dp,
            composerMaxWidth = 760.dp,
            navigationHeaderTopInset = 0.dp,
            sidebarHeadingHeight = 24.dp,
            messagePadding = 16.dp,
            messageCornerRadius = 12.dp,
            toolPadding = 8.dp,
            composerActionSize = 32.dp,
            composerPillHeight = 32.dp,
            isDesktop = true,
            navigationRowHeight = 28.dp,
            titlebarInset = 0.dp,
            editorMaxHeight = 168.dp,
            emptyStateVerticalBias = -0.14f,
        )
        val DesktopMacOs = Desktop.copy(titlebarInset = 32.dp, navigationHeaderTopInset = 32.dp)
    }
}
