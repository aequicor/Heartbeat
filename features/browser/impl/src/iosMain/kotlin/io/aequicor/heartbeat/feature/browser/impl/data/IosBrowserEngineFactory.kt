package io.aequicor.heartbeat.feature.browser.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.feature.browser.api.BrowserPage
import io.aequicor.heartbeat.feature.browser.impl.di.scope.BrowserScope
import io.aequicor.heartbeat.feature.browser.impl.domain.IosBrowserEngine
import io.aequicor.heartbeat.feature.browser.impl.domain.IosBrowserEngineFactory as EngineFactory

/** Every visible surface gets a fresh WebKit instance and a nonpersistent website data store. */
@ContributesBinding(BrowserScope::class)
@Inject
internal class IosBrowserEngineFactory : EngineFactory {
    override fun create(onChanged: (BrowserPage) -> Unit): IosBrowserEngine = WebKitBrowserEngine(onChanged)
}
