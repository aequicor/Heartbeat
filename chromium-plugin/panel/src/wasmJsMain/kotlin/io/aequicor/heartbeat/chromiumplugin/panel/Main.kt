package io.aequicor.heartbeat.chromiumplugin.panel

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.window.ComposeViewport
import io.aequicor.heartbeat.chromiumplugin.panel.link.PanelLink
import io.aequicor.heartbeat.chromiumplugin.panel.store.panelStore
import io.aequicor.heartbeat.chromiumplugin.panel.ui.PanelApp
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.theme.rememberHostTypography
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.configureWebResources

private val log = Log.tag("Panel")

/** Entry point of `app/panel.js`, started by `chromium-plugin/src/sidepanel/panel.html`. */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalResourceApi::class)
fun main() {
    // Logs go to the DevTools console of the side panel.
    Log.init(isDebug = true)
    // The page lives in src/sidepanel/, the build with its resources in app/ of the extension.
    configureWebResources { resourcePathMapping { path -> "/app/$path" } }
    val js = panelHostJs()
    if (js == null) {
        log.e { "panel host is missing: panel.html must load panel-boot.js first" }
        return
    }
    val dispatchers = BrowserDispatchers()
    // The store lives as long as the side panel page.
    val store = panelStore(HeartbeatStoreFactory(dispatchers), PanelLink(ChromePanelHost(js)))
    store.start(CoroutineScope(SupervisorJob() + dispatchers.main))
    log.i { "panel started" }
    ComposeViewport(PANEL_ELEMENT) {
        val fonts by produceState<PanelFonts?>(null) { value = loadFonts() }
        val resolver = LocalFontFamilyResolver.current
        val ready by produceState(false, fonts) {
            fonts?.fallbacks?.forEach { resolver.preload(it) }
            value = fonts != null
        }
        // Text laid out before the fallback fonts would keep empty boxes for emoji and symbols.
        if (ready) PanelTheme(fonts) { PanelApp(store) }
    }
}

@Composable
private fun PanelTheme(fonts: PanelFonts?, content: @Composable () -> Unit) {
    val host = rememberHostTypography(isDesktop = true)
    val code = fonts?.code
    val typography = remember(host, code) {
        if (code == null) host else host.copy(code = host.code.copy(fontFamily = code))
    }
    HbTheme(typography = typography, content = content)
}

/** The panel still works with system-less default fonts when the bundled ones cannot be read. */
private suspend fun loadFonts(): PanelFonts = try {
    PanelFonts.load()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    log.w(e) { "bundled fonts unavailable" }
    PanelFonts(emptyList(), code = null)
}

/** The browser page has one thread; `io` work shares the default dispatcher. */
private class BrowserDispatchers : DispatcherProvider {
    // The single place of the panel that references Dispatchers.*; the store receives this provider.
    @Suppress("InjectDispatcher")
    override val main: CoroutineDispatcher = Dispatchers.Main

    @Suppress("InjectDispatcher") // see main
    override val default: CoroutineDispatcher = Dispatchers.Default

    @Suppress("InjectDispatcher") // see main
    override val io: CoroutineDispatcher = Dispatchers.Default
}

/** Id of the element the panel draws into (`panel.html`). */
private const val PANEL_ELEMENT = "panel"
