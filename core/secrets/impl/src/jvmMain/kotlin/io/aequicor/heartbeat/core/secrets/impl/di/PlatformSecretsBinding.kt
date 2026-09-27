package io.aequicor.heartbeat.core.secrets.impl.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.core.secrets.impl.SecretsConfig
import io.aequicor.heartbeat.core.secrets.impl.data.JvmProtectedVault
import io.aequicor.heartbeat.core.secrets.impl.data.ProtectedVault

@ContributesBinding(AppScope::class)
@Inject
internal class PlatformSecretsBinding(platform: PlatformInfo, config: SecretsConfig = SecretsConfig()) :
    ProtectedVault by JvmProtectedVault(platform, config)
