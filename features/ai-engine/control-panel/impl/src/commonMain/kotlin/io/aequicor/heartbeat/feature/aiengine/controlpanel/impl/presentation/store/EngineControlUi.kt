package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store

import androidx.compose.runtime.Immutable
import io.aequicor.heartbeat.feature.aiengine.facade.api.DisabledReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalizedText
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagedEngine
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.toImmutableMap

/** Text an engine supplies (page and field titles); the screen picks the translation of its language. */
@Immutable
data class TextUi(val default: String, val byLanguage: ImmutableMap<String, String>) {
    /** The translation for [language], or the default. */
    fun resolve(language: String): String = byLanguage[language] ?: default
}

/** The panel's text of an engine's [LocalizedText]. */
fun LocalizedText.toUi(): TextUi = TextUi(default, byLanguage.toImmutableMap())

/** Whether an engine runs, is switched off by the user, or cannot run here. */
enum class EngineStatusUi { On, Off, Unavailable }

/** The status of a managed engine. */
fun ManagedEngine.statusUi(): EngineStatusUi = when {
    enablement.isEnabled -> EngineStatusUi.On
    enablement.isOnlyUserDisabled -> EngineStatusUi.Off
    else -> EngineStatusUi.Unavailable
}

/** Version the engine runs now, or null when unknown. */
fun ManagedEngine.versionUi(): String? = installation.current?.version

/** Reasons that make an engine unavailable, for the developer hint. */
fun ManagedEngine.blockingReasons(): Set<DisabledReason> = enablement.reasons - DisabledReason.DisabledByUser
