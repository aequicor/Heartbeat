package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store

import androidx.compose.runtime.Immutable
import io.aequicor.heartbeat.feature.aiengine.facade.api.Compatibility
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalizedText
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginState
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginSupport
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

/** Connection summary shown beside an engine name; it is not a request health check. */
enum class EngineConnectionStatusUi { NotConnected, Off, Connected }

/** The status supported by the saved management facts, with the profile switch taking priority. */
fun ManagedEngine.connectionStatusUi(hasSelectedModels: Boolean): EngineConnectionStatusUi = when {
    !enablement.isUserEnabled -> EngineConnectionStatusUi.Off

    enablement.isEnabled && hasSelectedModels && hasUsableRuntime() && hasSavedConnection() ->
        EngineConnectionStatusUi.Connected

    else -> EngineConnectionStatusUi.NotConnected
}

private fun ManagedEngine.hasUsableRuntime(): Boolean {
    if (availability != EngineAvailability.Available) return false
    if (spec.install == InstallSupport.BuiltIn) return true
    val current = installation.current ?: return false
    return current.source != InstallSource.Missing && current.isRunnable &&
        installation.compatibility == Compatibility.Verified
}

private fun ManagedEngine.hasSavedConnection(): Boolean = when (spec.login) {
    LoginSupport.Cli, LoginSupport.CliWithDeviceCode -> login is LoginState.SignedIn
    LoginSupport.Connections -> connections > 0
    LoginSupport.None -> true
}
