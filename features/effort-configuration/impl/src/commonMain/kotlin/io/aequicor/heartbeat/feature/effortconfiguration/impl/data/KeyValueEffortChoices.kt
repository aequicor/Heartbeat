package io.aequicor.heartbeat.feature.effortconfiguration.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.jsonKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortChoice
import io.aequicor.heartbeat.feature.effortconfiguration.impl.domain.EffortChoices
import kotlinx.serialization.builtins.ListSerializer

/** Effort choices in the profile key-value store, one JSON list. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class KeyValueEffortChoices(
    @ForScope(ProfileScope::class) private val stores: DataStores,
) : EffortChoices {

    private val log = Log.tag("KeyValueEffortChoices")
    private val store by lazy { stores.keyValue(SPEC) }

    override suspend fun load(): List<EffortChoice> {
        val choices = store.get(KEY).orEmpty()
        log.d { "read effort choices: ${choices.size}" }
        return choices
    }

    override suspend fun save(choices: List<EffortChoice>) {
        log.d { "write effort choices: ${choices.size}" }
        store.set(KEY, choices)
    }

    private companion object {
        val SPEC = KeyValueSpec("effort_configuration")
        val KEY = jsonKey("choices", ListSerializer(EffortChoice.serializer()))
    }
}
