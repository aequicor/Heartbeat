package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import ai.koog.prompt.llm.LLModel
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogConnection
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogProvider
import io.aequicor.heartbeat.feature.aiengine.koog.api.koogProvider
import kotlinx.coroutines.CancellationException

/** Profile-memory vendor catalog capacities, isolated by credential revision and endpoint; main dispatcher only. */
@SingleIn(ProfileScope::class)
@Inject
internal class KoogContextWindows {
    private val log = Log.tag("KoogContextWindows")
    private val capacities = mutableMapOf<AuthSource, Map<String, Long>>()

    fun record(connection: KoogConnection, models: List<LLModel>) {
        val provider = koogProvider(connection.source)
        capacities[connection.source] = models.mapNotNull { model ->
            provider?.catalogContextCapacity(model)?.let { model.id to it }
        }.toMap()
        log.d { "Recorded model capacities for ${models.size} models" }
    }

    suspend fun resolve(connection: KoogConnection, model: String, client: KoogClient): Long? {
        if (koogProvider(connection.source)?.isCatalogCapacityTrusted != true) return null
        if (connection.source !in capacities) {
            try {
                record(connection, client.models())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w(e.sanitized()) { "Model capacity unavailable; context usage stays hidden" }
                capacities[connection.source] = emptyMap()
            }
        }
        return capacities[connection.source]?.get(model)
    }
}

/**
 * Koog fills known ids from vendor tables even on compatible endpoints. Those tables are authoritative only
 * on the fixed vendor route: aliases and local servers can configure different limits. Ollama's 4096 default
 * is likewise not its effective num_ctx. Other routes remain unknown until they report an effective capacity.
 */
internal val KoogProvider.isCatalogCapacityTrusted: Boolean
    get() = this == KoogProvider.OpenAI || this == KoogProvider.Anthropic

internal fun KoogProvider.catalogContextCapacity(model: LLModel): Long? =
    model.contextLength?.takeIf { isCatalogCapacityTrusted && it > 0 }
