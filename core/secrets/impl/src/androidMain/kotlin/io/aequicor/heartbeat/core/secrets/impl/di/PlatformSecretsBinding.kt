package io.aequicor.heartbeat.core.secrets.impl.di

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.secrets.impl.data.AndroidProtectedVault
import io.aequicor.heartbeat.core.secrets.impl.data.ProtectedVault

@ContributesBinding(AppScope::class)
@Inject
internal class PlatformSecretsBinding(context: Context) : ProtectedVault by AndroidProtectedVault(context)
