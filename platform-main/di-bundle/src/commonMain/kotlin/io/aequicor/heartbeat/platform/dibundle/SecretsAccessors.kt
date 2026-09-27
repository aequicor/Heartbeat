package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.ContributesTo
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.secrets.SecretStore

/** Profile graph entry point; normal consumers inject [SecretStore] directly. */
@ContributesTo(ProfileScope::class)
public interface SecretsAccessors {
    /** Protected storage of this profile. */
    public val secrets: SecretStore
}
