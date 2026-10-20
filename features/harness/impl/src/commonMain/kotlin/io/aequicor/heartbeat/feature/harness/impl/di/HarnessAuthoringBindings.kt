package io.aequicor.heartbeat.feature.harness.impl.di

import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.HarnessCodeChecks
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessScriptHost
import okio.ByteString.Companion.encodeUtf8

/** One profile memo of compile checks shared by agent tools and the item editor. */
@ContributesTo(ProfileScope::class)
@BindingContainer
public object HarnessAuthoringBindings {
    @Provides
    @SingleIn(ProfileScope::class)
    internal fun checks(host: HarnessScriptHost): HarnessCodeChecks =
        HarnessCodeChecks(host) { it.encodeUtf8().sha256().hex() }
}
