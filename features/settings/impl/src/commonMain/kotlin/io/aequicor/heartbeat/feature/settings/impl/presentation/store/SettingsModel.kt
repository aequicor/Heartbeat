package io.aequicor.heartbeat.feature.settings.impl.presentation.store

import androidx.compose.runtime.Immutable
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.feature.settings.api.SettingsSection
import io.aequicor.heartbeat.feature.settings.impl.di.scope.SettingsScope
import io.aequicor.heartbeat.feature.settings.impl.domain.SettingsSections
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState
import pro.respawn.flowmvi.plugins.reduce

/** A settings section as the screen knows it; mapped from and to the contract's [SettingsSection]. */
enum class SettingsSectionUi { Models, Search, FeatureFlags }

/** Screen value of a contract section. */
fun SettingsSection.toUi(): SettingsSectionUi = when (this) {
    SettingsSection.Models -> SettingsSectionUi.Models
    SettingsSection.Search -> SettingsSectionUi.Search
    SettingsSection.FeatureFlags -> SettingsSectionUi.FeatureFlags
}

/** Contract section of a screen value. */
fun SettingsSectionUi.toSection(): SettingsSection = when (this) {
    SettingsSectionUi.Models -> SettingsSection.Models
    SettingsSectionUi.Search -> SettingsSection.Search
    SettingsSectionUi.FeatureFlags -> SettingsSection.FeatureFlags
}

/**
 * State of the settings window. [selected] is the section shown next to the list; [requested] is the section
 * asked for by the route or a deep link until it becomes available. Narrow windows show either the list or one
 * section: the list while no section was chosen explicitly ([isSectionChosen]) or after "back" ([isListShown]).
 */
@Immutable
data class SettingsScreenState(
    val sections: ImmutableList<SettingsSectionUi> = persistentListOf(),
    val selected: SettingsSectionUi? = null,
    val requested: SettingsSectionUi? = null,
    val isListShown: Boolean = false,
    val isSectionChosen: Boolean = false,
) : MVIState {
    /** Whether a compact window shows the section list rather than a section. */
    val isCompactListShown: Boolean get() = isListShown || !isSectionChosen || selected == null
}

/** User and data events of the settings window. */
sealed interface SettingsScreenIntent : MVIIntent {
    /** Opens [section] (the list step is left on compact layouts). */
    data class Select(val section: SettingsSectionUi) : SettingsScreenIntent

    /** Asks for [section] once it is available (route or deep link). */
    data class Request(val section: SettingsSectionUi?) : SettingsScreenIntent

    /** Returns to the section list on compact layouts. */
    data object ShowList : SettingsScreenIntent

    /** Sections offered now changed (toggles or profile). */
    data class SectionsChanged(val sections: ImmutableList<SettingsSectionUi>) : SettingsScreenIntent
}

/** No one-off actions: navigation follows [SettingsScreenState.selected]. */
sealed interface SettingsScreenAction : MVIAction

/** Screen store of the settings window; it only chooses sections, their content belongs to their features. */
@SingleIn(SettingsScope::class)
@Inject
class SettingsModel(
    source: SettingsSections,
    @ForScope(SettingsScope::class) scope: ScopeHandle,
    factory: HeartbeatStoreFactory,
) {
    private val log = Log.tag("SettingsModel")

    val store = factory.create<SettingsScreenState, SettingsScreenIntent, SettingsScreenAction>(
        "Settings",
        SettingsScreenState(),
        onError = { this },
    ) {
        reduce { intent ->
            when (intent) {
                is SettingsScreenIntent.Select -> updateState {
                    if (intent.section in sections) {
                        copy(selected = intent.section, isListShown = false, isSectionChosen = true)
                    } else {
                        this
                    }
                }

                is SettingsScreenIntent.Request -> updateState {
                    val resolved = copy(isSectionChosen = isSectionChosen || intent.section != null)
                        .resolve(sections, intent.section ?: selected)
                    if (resolved.requested != null) {
                        val shown = resolved.selected?.name ?: "none"
                        log.i { "section ${resolved.requested} is not available yet; showing $shown" }
                    }
                    resolved
                }

                SettingsScreenIntent.ShowList -> updateState { copy(isListShown = true) }

                is SettingsScreenIntent.SectionsChanged -> updateState {
                    // Until the user picks a section the window follows the first available one.
                    copy(sections = intent.sections)
                        .resolve(intent.sections, requested ?: selected.takeIf { isSectionChosen })
                }
            }
        }
    }

    init {
        store.start(scope.coroutineScope)
        scope.coroutineScope.launch {
            source.available.collect {
                log.i { "sections available=${it.joinToString { section -> section.name }}" }
                store.intent(SettingsScreenIntent.SectionsChanged(it.map(SettingsSection::toUi).toImmutableList()))
            }
        }
    }
}

/**
 * Shows [wanted] when it is available and otherwise keeps the current section or falls back to the first one;
 * an unavailable [wanted] stays requested until toggles or the profile make it available.
 */
internal fun SettingsScreenState.resolve(
    available: List<SettingsSectionUi>,
    wanted: SettingsSectionUi?,
): SettingsScreenState = when {
    wanted != null && wanted in available -> copy(selected = wanted, requested = null)
    selected != null && selected in available -> copy(requested = wanted)
    else -> copy(selected = available.firstOrNull(), requested = wanted?.takeIf { it !in available })
}
