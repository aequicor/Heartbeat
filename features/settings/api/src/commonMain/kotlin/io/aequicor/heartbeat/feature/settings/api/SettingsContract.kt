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

    /** Adaptive subagents: selection preferences, service models, connection billing and model descriptions. */
    @SerialName("subagents")
    Subagents,

    /** Search providers of the profile. */
    @SerialName("search")
    Search,

    /** Enables or disables agent computer-use tools. */
    @SerialName("computer_use")
    ComputerUse,

    /** The Chromium browser bridge: host registration, connected extensions and agent tabs. */
    @SerialName("chromium_browser")
    ChromiumBrowser,

    /** Instructions the agent learned and how new ones are accepted. */
    @SerialName("agent_learning")
    AgentLearning,

    /** Context window optimization: automatic compaction and how the chat feed shows reasoning and tools. */
    @SerialName("context")
    Context,

    /** The machine-wide build queue (Desktop): automatic or user limit of parallel agent builds. */
    @SerialName("builds")
    Builds,

    /** Harnesses the agent built: their approval level, scope, items, tools and workflow runs. */
    @SerialName("harness")
    Harness,

    /** Interfaces the agent built for harnesses: their switches, specs and previews. */
    @SerialName("interfaces")
    Interfaces,

    /** Project `.heartbeat` folders, changes waiting for trust and `.hbai` transfer. */
    @SerialName("project_config")
    ProjectConfig,

    /** Desktop application diagnostics: recent logs, archive and the logs directory. */
    @SerialName("logs")
    Logs,

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
        SettingsSection.Subagents -> "subagents"
        SettingsSection.Search -> "search"
        SettingsSection.ComputerUse -> "computer_use"
        SettingsSection.ChromiumBrowser -> "chromium_browser"
        SettingsSection.AgentLearning -> "agent_learning"
        SettingsSection.Context -> "context"
        SettingsSection.Builds -> "builds"
        SettingsSection.Harness -> "harness"
        SettingsSection.Interfaces -> "interfaces"
        SettingsSection.ProjectConfig -> "project_config"
        SettingsSection.Logs -> "logs"
        SettingsSection.FeatureFlags -> "feature_flags"
    }

/** Section addressed by a deep link path segment, or `null` for an unknown one. */
public fun settingsSectionOf(deepLinkName: String): SettingsSection? =
    SettingsSection.entries.firstOrNull { it.deepLinkName == deepLinkName }
