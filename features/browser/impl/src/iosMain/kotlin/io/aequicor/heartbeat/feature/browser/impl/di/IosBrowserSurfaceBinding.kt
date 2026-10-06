package io.aequicor.heartbeat.feature.browser.impl.di

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import io.aequicor.heartbeat.feature.browser.impl.di.scope.BrowserScope
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserSession
import io.aequicor.heartbeat.feature.browser.impl.domain.IosBrowserEngineFactory
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserSurface
import io.aequicor.heartbeat.feature.browser.impl.presentation.IosBrowserSurface

/**
 * Adds the platform engine factory to the retained session's surface in the composition root.
 * BrowserSession is supplied by the common BrowserSurfaceAdapter, which implements both contracts;
 * only the BrowserSurface binding is overridden, so both still share the same retained session.
 */
@SingleIn(BrowserScope::class)
@ContributesBinding(BrowserScope::class, binding = binding<BrowserSurface>(), priority = 1)
@Inject
internal class IosBrowserSurfaceBinding(session: BrowserSession, override val engines: IosBrowserEngineFactory) :
    IosBrowserSurface,
    BrowserSurface by (session as BrowserSurface)
