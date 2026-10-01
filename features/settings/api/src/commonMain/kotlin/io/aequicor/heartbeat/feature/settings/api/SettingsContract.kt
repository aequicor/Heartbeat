package io.aequicor.heartbeat.feature.settings.api

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.navigation.Route
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Sections of the unified settings, in display order. Each section hosts an existing feature's route inside the
 * settings window; the settings feature owns only the list of sections and the window chrome.
 */
@Serializable
public enum class SettingsSection {
    /** Engines, their connections and models, including the connection wizard. */
    @SerialName("models")
    Models,

    /** Search providers of the profile. */
    @SerialName("search")
    Search,

    /** Desktop capture, frame previews and explicit input permission. */
    @SerialName("computer_use")
    ComputerUse,

    /** Device-local feature flags. */
    @SerialName("feature_flags")
    FeatureFlags,
}

/**
 * The unified settings window: a list of sections next to the content of the selected one ([section], or the
 * first available section). Opened from anywhere (studio button, ⌘, and deep links `settings` /
 * `settings/<section>`); registered in the app tree, so the guest tree shows the sections that need no profile.
 *
 * There is one settings window: routes are equal whatever their [section], so `SingleTop` never stacks a second
 * window when settings are already on top (the open window keeps its section).
 */
@Serializable
@SerialName("settings")
public class SettingsRoute(public val section: SettingsSection? = null) : Route {
    override fun equals(other: Any?): Boolean = other is SettingsRoute

    override fun hashCode(): Int = SettingsRoute::class.hashCode()

    override fun toString(): String = "SettingsRoute(section=${section?.name ?: "default"})"
}

/**
 * One "Settings" entry point with all sections in one window instead of separate screens over the studio.
 * On by default: the settings window supports both desktop and compact layouts, and switching it off restores
 * the previous separate screens.
 */
public val UnifiedSettings: FeatureToggle.Flag = FeatureToggle.Flag(
    "settings.unified",
    "Единые настройки: одна кнопка и все разделы в одном окне",
    default = true,
)

/** Deep link path of [section], e.g. `settings/feature_flags`. */
public val SettingsSection.deepLinkName: String
    get() = when (this) {
        SettingsSection.Models -> "models"
        SettingsSection.Search -> "search"
        SettingsSection.ComputerUse -> "computer_use"
        SettingsSection.FeatureFlags -> "feature_flags"
    }

/** Section addressed by a deep link path segment, or `null` for an unknown one. */
public fun settingsSectionOf(deepLinkName: String): SettingsSection? =
    SettingsSection.entries.firstOrNull { it.deepLinkName == deepLinkName }
