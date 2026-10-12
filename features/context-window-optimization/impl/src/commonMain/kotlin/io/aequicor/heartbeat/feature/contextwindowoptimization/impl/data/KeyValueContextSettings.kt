package io.aequicor.heartbeat.feature.contextwindowoptimization.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.jsonKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.CompactionStrategy
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationSettings
import io.aequicor.heartbeat.feature.contextwindowoptimization.impl.domain.ContextSettingsStore
import kotlinx.serialization.Serializable

/**
 * Context optimization settings in the profile key-value store, one JSON document. Values outside the allowed ranges
 * are brought into them on load, so an invalid stored value never discards the other settings.
 */
@ContributesBinding(ProfileScope::class)
@Inject
internal class KeyValueContextSettings(
    @ForScope(ProfileScope::class) private val stores: DataStores,
) : ContextSettingsStore {
    private val log = Log.tag("KeyValueContextSettings")
    private val store by lazy { stores.keyValue(SPEC) }

    override suspend fun load(): ContextOptimizationSettings? = store.get(KEY)?.toSettings().also {
        log.v { "read context settings: stored=${it != null}" }
    }

    override suspend fun save(settings: ContextOptimizationSettings) {
        log.v { "write context settings" }
        store.set(KEY, StoredSettings(settings))
    }

    private companion object {
        val SPEC = KeyValueSpec("context_optimization")
        val KEY = jsonKey("settings", StoredSettings.serializer())
    }
}

/** The stored form of [ContextOptimizationSettings]: the same JSON, read without its range checks. */
@Serializable
internal data class StoredSettings(
    val thresholdPercent: Int = ContextOptimizationSettings.DEFAULT_THRESHOLD_PERCENT,
    val strategy: CompactionStrategy = CompactionStrategy.Auto,
    val isAutoCompactionEnabled: Boolean = true,
    val koogKeepRecentTurns: Int = ContextOptimizationSettings.DEFAULT_KEEP_RECENT_TURNS,
    val koogToolStubChars: Int = ContextOptimizationSettings.DEFAULT_TOOL_STUB_CHARS,
    val offloadThresholdChars: Int = ContextOptimizationSettings.DEFAULT_OFFLOAD_THRESHOLD_CHARS,
    val isReasoningCollapsed: Boolean = false,
    val isToolCallsCollapsed: Boolean = false,
) {
    constructor(settings: ContextOptimizationSettings) : this(
        settings.thresholdPercent,
        settings.strategy,
        settings.isAutoCompactionEnabled,
        settings.koogKeepRecentTurns,
        settings.koogToolStubChars,
        settings.offloadThresholdChars,
        settings.isReasoningCollapsed,
        settings.isToolCallsCollapsed,
    )

    /** The settings with every value brought into its allowed range. */
    fun toSettings(): ContextOptimizationSettings = ContextOptimizationSettings(
        thresholdPercent = thresholdPercent.coerceIn(ContextOptimizationSettings.THRESHOLD_RANGE),
        strategy = strategy,
        isAutoCompactionEnabled = isAutoCompactionEnabled,
        koogKeepRecentTurns = koogKeepRecentTurns.coerceIn(ContextOptimizationSettings.KEEP_RECENT_TURNS_RANGE),
        koogToolStubChars = koogToolStubChars.coerceIn(ContextOptimizationSettings.TOOL_STUB_CHARS_RANGE),
        offloadThresholdChars = offloadThresholdChars.coerceIn(ContextOptimizationSettings.OFFLOAD_THRESHOLD_RANGE),
        isReasoningCollapsed = isReasoningCollapsed,
        isToolCallsCollapsed = isToolCallsCollapsed,
    )
}
