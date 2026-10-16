package io.aequicor.heartbeat.feature.projectconfig.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.stringKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigBinding
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ConfigBindingStorage
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Bindings in the profile key-value store as one JSON list. An unreadable list fails the load, so the next save cannot
 * replace it with nothing; decoder messages are dropped because they quote stored values.
 */
@ContributesBinding(ProfileScope::class)
@Inject
internal class KeyValueConfigBindings(
    @ForScope(ProfileScope::class) private val stores: DataStores,
) : ConfigBindingStorage {
    private val log = Log.tag("ConfigBindings")
    private val store by lazy { stores.keyValue(SPEC) }

    override suspend fun load(): List<ConfigBinding> {
        val bindings = store.get(BINDINGS)?.let(::decode).orEmpty()
        log.d { "Read configuration bindings: ${bindings.size}" }
        return bindings
    }

    override suspend fun save(bindings: List<ConfigBinding>) {
        log.d { "Write configuration bindings: ${bindings.size}" }
        store.set(BINDINGS, json.encodeToString(LIST, bindings))
    }

    private fun decode(raw: String): List<ConfigBinding> = try {
        json.decodeFromString(LIST, raw)
    } catch (e: IllegalArgumentException) {
        throw e.withoutStoredValues()
    }

    private companion object {
        val SPEC = KeyValueSpec("project_config")
        val BINDINGS = stringKey("bindings")
        val LIST = ListSerializer(ConfigBinding.serializer())
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** Decoder messages and causes quote stored values: only the failure kind is kept. */
private fun Exception.withoutStoredValues(): UnreadableBindings = UnreadableBindings(this::class.simpleName.orEmpty())

/** The stored bindings are not a readable list; [kind] is the decoder failure class. */
internal class UnreadableBindings(kind: String) : IllegalStateException("Unreadable bindings ($kind)")
