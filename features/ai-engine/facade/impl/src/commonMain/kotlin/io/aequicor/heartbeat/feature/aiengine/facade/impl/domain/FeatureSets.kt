package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeature
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import kotlin.coroutines.cancellation.CancellationException
import kotlin.reflect.safeCast

/** No optional operations. */
object NoEngineFeatures : EngineFeatures {
    override fun <F : EngineFeature> resolve(key: EngineFeatureKey<F>): FeatureAccess<F> = FeatureAccess.Unsupported
}

/** Every operation is blocked by [reason], e.g. a disabled engine or a closed handle. */
class BlockedEngineFeatures(private val reason: EngineFailure) : EngineFeatures {
    override fun <F : EngineFeature> resolve(key: EngineFeatureKey<F>): FeatureAccess<F> =
        FeatureAccess.Unavailable(reason)
}

/**
 * Capability table resolved without IO. An entry is returned only when it implements the key's contract, so a
 * matching id alone never leads to an unchecked cast; ids absent from the table fall back to [fallback].
 */
class FeatureTable(
    private val entries: Map<EngineFeatureId, () -> FeatureAccess<EngineFeature>>,
    private val fallback: EngineFeatures = NoEngineFeatures,
) : EngineFeatures {
    override fun <F : EngineFeature> resolve(key: EngineFeatureKey<F>): FeatureAccess<F> {
        val entry = entries[key.id] ?: return fallback.resolve(key)
        return when (val access = entry()) {
            is FeatureAccess.Available -> key.type.safeCast(access.feature)
                ?.let { FeatureAccess.Available(it) }
                ?: FeatureAccess.Unsupported

            is FeatureAccess.Unavailable -> access

            FeatureAccess.Unsupported -> FeatureAccess.Unsupported
        }
    }
}

/** Table entry for a feature that is always available. */
fun available(feature: EngineFeature): () -> FeatureAccess<EngineFeature> = { FeatureAccess.Available(feature) }

/** Table entry exposing [wrap] of the [key] contract of these features, keeping their availability. */
fun <F : EngineFeature> EngineFeatures.wrap(
    key: EngineFeatureKey<F>,
    wrap: (F) -> EngineFeature,
): () -> FeatureAccess<EngineFeature> = {
    when (val access = resolve(key)) {
        is FeatureAccess.Available -> FeatureAccess.Available(wrap(access.feature))
        is FeatureAccess.Unavailable -> access
        FeatureAccess.Unsupported -> FeatureAccess.Unsupported
    }
}

/** Supported capabilities become Unavailable while [blocker] reports a reason, e.g. a closed handle. */
class GatedFeatures(private val inner: EngineFeatures, private val blocker: () -> EngineFailure?) : EngineFeatures {
    override fun <F : EngineFeature> resolve(key: EngineFeatureKey<F>): FeatureAccess<F> {
        val access = inner.resolve(key)
        if (access == FeatureAccess.Unsupported) return access
        return blocker()?.let { FeatureAccess.Unavailable(it) } ?: access
    }
}

/** The available feature; a blocked or absent capability is reported as the matching domain failure. */
fun <F : EngineFeature> FeatureAccess<F>.orFail(): F = when (this) {
    is FeatureAccess.Available -> feature
    is FeatureAccess.Unavailable -> fail(reason)
    FeatureAccess.Unsupported -> fail(EngineFailure.Engine(EngineFailureReason.UnsupportedCapability))
}

/**
 * Calls an adapter: domain failures pass through, anything else is logged and reported as Unknown so native
 * exception text never reaches consumers.
 */
suspend fun <T> adapterCall(log: Log, operation: String, block: suspend () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: EngineException) {
    log.w(e) { "adapter $operation failed failure=${e.failure.code}" }
    throw e
} catch (e: Exception) {
    log.e(e) { "adapter $operation crashed" }
    fail(EngineFailure.Unknown())
}
