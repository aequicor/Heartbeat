package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueReadPolicy
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.core.datastore.stringKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentSecuritySettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.SecurityMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen

/** Keeps the existing storage identity: upgrading never resets the user's chosen permission mode. */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
internal class ProfileSecuritySettings(private val store: KeyValueStore) : AgentSecuritySettings {
    @Inject
    constructor(
        @ForScope(ProfileScope::class) stores: DataStores,
    ) : this(stores.keyValue(SecuritySpec))

    private val log = Log.tag("SecuritySettings")

    override val modes = store.observe(LevelKey)
        .map { value ->
            try {
                decode(value) ?: SecurityMode.Automatic
            } catch (error: IllegalStateException) {
                log.w(error.redactedFailure()) { "Stored security mode is invalid; asking until repaired" }
                SecurityMode.Ask
            }
        }
        .retryWhen { error, _ ->
            if (error is CancellationException) throw error
            reportUnavailable(error)
            emit(SecurityMode.Ask)
            delay(1_000)
            true
        }
        .distinctUntilChanged()

    private fun reportUnavailable(error: Throwable) {
        log.w(IllegalStateException("Security setting unavailable (${error::class.simpleName.orEmpty()})")) {
            "Permission setting could not be loaded; user confirmation is required"
        }
    }

    override suspend fun load(): SecurityMode? = decode(store.get(LevelKey))

    override suspend fun save(mode: SecurityMode) {
        store.set(LevelKey, mode.name)
        log.i { "Profile permission mode saved: $mode" }
    }
}

private fun decode(value: String?): SecurityMode? = value?.let { saved ->
    SecurityMode.entries.firstOrNull { it.name == saved } ?: error("Unknown saved security mode")
}

private val SecuritySpec = KeyValueSpec("ai_studio_security", readPolicy = KeyValueReadPolicy.Strict)
private val LevelKey = stringKey("confirmation_level")
