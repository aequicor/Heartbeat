package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Multibinds
import io.aequicor.heartbeat.core.di.AppStartup

/** The desktop host starts app-scoped work once after creating the graph: `(graph as AppStartupAccessors)`. */
@ContributesTo(AppScope::class)
interface AppStartupAccessors {
    /** Work of features that runs for the whole launch, such as the app update check. */
    val appStartups: Set<AppStartup>
}

/** The desktop starts normally before any feature contributes app-scoped work. */
@ContributesTo(AppScope::class)
@BindingContainer
interface AppStartupBindings {
    @Multibinds(allowEmpty = true)
    fun appStartups(): Set<AppStartup>
}
