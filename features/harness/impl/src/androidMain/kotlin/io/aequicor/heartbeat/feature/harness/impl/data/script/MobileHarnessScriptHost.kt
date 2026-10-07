package io.aequicor.heartbeat.feature.harness.impl.data.script

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessScriptHost
import io.aequicor.heartbeat.feature.harness.impl.domain.script.UnsupportedHarnessScriptHost

/** The compiler remains unavailable on this platform; library persistence is unaffected. */
@ContributesBinding(ProfileScope::class)
@SingleIn(ProfileScope::class)
@Inject
internal class MobileHarnessScriptHost : HarnessScriptHost by UnsupportedHarnessScriptHost()
