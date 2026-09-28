package io.aequicor.heartbeat.ds.tokens

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Opaque window surfaces of the shared application shell, derived from the [HbColors] preset of the same host.
 * [backdrop] fills the window, [sidebar] the single edge-to-edge sidebar (studio sessions, settings sections),
 * [header] pane headers. [outgoing] distinguishes user messages; [selected] and [onSelected] mark the active row.
 * [assistant] is the reading surface of answers; [tool] and [console] are subordinate surfaces inside it and
 * [outline] separates their edges. [accent] and [success] are readable foregrounds, not pastel fills.
 * Composer roles distinguish its surface, quiet and lavender pills and the high-contrast send action.
 * No role is translucent: the flat style has no glass, ambient light fields or decorative gradients.
 */
@Immutable
data class HbSurfaceColors(
    val backdrop: Color,
    val rail: Color,
    val sidebar: Color,
    val header: Color,
    val outgoing: Color,
    val selected: Color,
    val onSelected: Color,
    val avatar: Color,
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
    /** Presets matching [HbColors.Light], [HbColors.Dark], [HbColors.DesktopLight] and [HbColors.DesktopDark]. */
    companion object {
        val Light = HbSurfaceColors(
            backdrop = HbColors.Light.background,
            rail = Color(0xFFF0F5FA),
            sidebar = Color(0xFFF0F5FA),
            header = Color(0xFFFFFFFF),
            outgoing = Color(0xFFE2DCFF),
            selected = Color(0xFFE6E0FC),
            onSelected = HbColors.Light.textPrimary,
            avatar = Color(0xFFF7F5FF),
            assistant = Color.White,
            composer = Color.White,
            tool = Color(0xFFF6F8FC),
            console = Color(0xFFEEF2F8),
            outline = Color(0xFFDCE4EF),
            accent = Color(0xFF6650D1),
            success = Color(0xFF13783D),
            composerAction = Color(0xFF30364C),
            onComposerAction = Color.White,
            composerPill = Color(0xFFF2F5F9),
            composerPillAccent = Color(0xFFECE6FF),
            onComposerPillAccent = Color(0xFF583ACC),
        )
        val Dark = HbSurfaceColors(
            backdrop = HbColors.Dark.background,
            rail = Color(0xFF222B3A),
            sidebar = Color(0xFF222B3A),
            header = Color(0xFF273243),
            outgoing = Color(0xFF46405E),
            selected = Color(0xFF433C5D),
            onSelected = HbColors.Dark.textPrimary,
            avatar = Color(0xFF39364F),
            assistant = Color(0xFF293344),
            composer = Color(0xFF2C3749),
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
        val DesktopLight = Light.copy(
            backdrop = HbColors.DesktopLight.background,
            rail = Color(0xFFF4F4F5),
            sidebar = Color(0xFFF4F4F5),
            header = Color.White,
            outgoing = Color(0xFFF1EEF6),
            selected = HbColors.DesktopLight.selectedContainer,
            onSelected = HbColors.DesktopLight.textPrimary,
            avatar = Color(0xFFEFEAF4),
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
            backdrop = HbColors.DesktopDark.background,
            rail = Color(0xFF28282B),
            sidebar = Color(0xFF28282B),
            header = Color(0xFF202022),
            outgoing = Color(0xFF35313B),
            selected = HbColors.DesktopDark.selectedContainer,
            onSelected = HbColors.DesktopDark.textPrimary,
            avatar = Color(0xFF3A3442),
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

        /** Preset of a host, mirroring [HbColors.forHost]. */
        fun forHost(isDark: Boolean, isDesktop: Boolean): HbSurfaceColors = when {
            isDesktop && isDark -> DesktopDark
            isDesktop -> DesktopLight
            isDark -> Dark
            else -> Light
        }
    }
}
