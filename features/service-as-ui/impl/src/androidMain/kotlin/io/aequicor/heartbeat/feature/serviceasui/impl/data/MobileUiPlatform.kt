package io.aequicor.heartbeat.feature.serviceasui.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiPlatform

/** Surfaces on mobile platforms neither save, reveal nor pick files. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class MobileUiPlatform : UiPlatform by UnsupportedUiPlatform
