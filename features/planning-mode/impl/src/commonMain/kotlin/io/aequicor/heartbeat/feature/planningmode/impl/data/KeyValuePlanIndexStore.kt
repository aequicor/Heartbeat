package io.aequicor.heartbeat.feature.planningmode.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueReadPolicy
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.jsonKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.planningmode.impl.domain.PlanIndex
import io.aequicor.heartbeat.feature.planningmode.impl.domain.PlanIndexStore

/**
 * The plan index in the profile key-value store. Reads are strict: an unreadable index fails the load, so the next
 * save never replaces plans it could not read.
 */
@ContributesBinding(ProfileScope::class)
@Inject
internal class KeyValuePlanIndexStore(
    @ForScope(ProfileScope::class) private val stores: DataStores,
) : PlanIndexStore {
    private val store by lazy { stores.keyValue(SPEC) }

    override suspend fun load(): PlanIndex? = store.get(INDEX)

    override suspend fun save(index: PlanIndex) {
        store.set(INDEX, index)
    }

    private companion object {
        val SPEC = KeyValueSpec("planning_mode", readPolicy = KeyValueReadPolicy.Strict)
        val INDEX = jsonKey("index", PlanIndex.serializer())
    }
}
