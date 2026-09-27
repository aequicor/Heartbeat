package io.aequicor.heartbeat.ds.catalog

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.ds.adaptive.PlatformUi
import io.aequicor.heartbeat.ds.resources.HbLocale
import io.aequicor.heartbeat.ds.resources.HbString
import io.aequicor.heartbeat.ds.theme.HbVisualStyle

private val log = Log.tag("DS/Sandbox")

internal enum class SandboxPage(val title: HbString) {
    Foundation(HbString.Foundation),
    Icons(HbString.Icons),
    Illustrations(HbString.Illustrations),
    Components(HbString.Components),
    Layouts(HbString.Layouts),
    Chat(HbString.Chat),
}

internal enum class SandboxTheme(val title: HbString) {
    System(HbString.System),
    Light(HbString.Light),
    Dark(HbString.Dark),
}

/** Local presentation state for a component catalog, not an application feature or a business store. */
@Stable
internal class SandboxState(platform: PlatformUi, language: HbLocale, darkTheme: Boolean?, style: HbVisualStyle) {
    var page by mutableStateOf(SandboxPage.Chat)
        private set
    var theme by mutableStateOf(
        when (darkTheme) {
            true -> SandboxTheme.Dark
            false -> SandboxTheme.Light
            null -> SandboxTheme.System
        },
    )
        private set
    var platformUi by mutableStateOf(platform)
        private set
    var visualStyle by mutableStateOf(style)
        private set
    var locale by mutableStateOf(language)
        private set
    var input by mutableStateOf("")
        private set
    var hasActionFeedback by mutableStateOf(false)
        private set

    fun selectPage(value: SandboxPage) {
        log.i { "catalog page changed from=$page to=$value" }
        page = value
    }

    fun selectTheme(value: SandboxTheme) {
        log.i { "catalog theme changed from=$theme to=$value" }
        theme = value
    }

    fun selectPlatform(value: PlatformUi) {
        log.i { "catalog platform changed from=$platformUi to=$value" }
        platformUi = value
    }

    fun selectLocale(value: HbLocale) {
        log.i { "catalog language changed from=$locale to=$value" }
        locale = value
    }

    fun selectVisualStyle(value: HbVisualStyle) {
        log.i { "catalog visual style changed from=$visualStyle to=$value" }
        visualStyle = value
    }

    fun updateInput(value: String) {
        log.d { "catalog sample input updated length=${value.length}" }
        input = value
    }

    fun showActionFeedback() {
        log.i { "catalog sample action completed" }
        hasActionFeedback = true
    }
}
