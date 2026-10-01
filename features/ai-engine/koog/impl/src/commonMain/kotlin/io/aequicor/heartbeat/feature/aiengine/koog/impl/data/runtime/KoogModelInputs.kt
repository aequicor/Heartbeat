package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import ai.koog.prompt.llm.LLModel
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptInputSupport
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogConnection
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogProvider
import io.aequicor.heartbeat.feature.aiengine.koog.api.koogProvider

/** Capability cache is pinned to the complete credential route, including source revision and origin. */
@SingleIn(ProfileScope::class)
@Inject
internal class KoogModelInputs(
    private val catalog: KoogReasoningCatalog = object : KoogReasoningCatalog {
        override suspend fun levels(provider: KoogProvider, model: String): List<String>? = null
    },
) {
    private val values = mutableMapOf<Pair<KoogConnection, String>, PromptInputSupport>()

    suspend fun remember(connection: KoogConnection, model: LLModel): PromptInputSupport {
        val provider = koogProvider(connection.source)
        val vendor = catalogProvider(connection)
        // OpenAI/Anthropic SDKs decorate listed IDs with static vendor capabilities, not endpoint modalities.
        val native = if (provider == KoogProvider.Ollama ||
            vendor in setOf(KoogProvider.OpenAI, KoogProvider.Anthropic)
        ) {
            koogInputSupport(model)
        } else {
            PromptInputSupport.TextDocuments
        }
        val reported = if (vendor != null) catalog.inputSupport(vendor, model.id) else null
        val support = providerInputSupport(
            provider,
            native.copy(
                imageMediaTypes = native.imageMediaTypes + reported?.imageMediaTypes.orEmpty(),
                resourceMediaTypes = native.resourceMediaTypes + reported?.resourceMediaTypes.orEmpty(),
            ),
        )
        values[connection to model.id] = support
        return support
    }

    fun forget(connection: KoogConnection, model: String) {
        values.remove(connection to model)
    }

    fun cached(connection: KoogConnection, model: String): PromptInputSupport =
        values[connection to model] ?: providerInputSupport(
            koogProvider(connection.source),
            PromptInputSupport.TextDocuments,
        )

    fun cached(binding: EngineBindingId, model: String): PromptInputSupport =
        values.entries.lastOrNull { it.key.first.binding.id == binding && it.key.second == model }?.value
            ?: PromptInputSupport.TextDocuments
}

private fun catalogProvider(connection: KoogConnection): KoogProvider? {
    val vendor = when (koogProvider(connection.source)) {
        KoogProvider.OpenAI, KoogProvider.OpenAICompatible -> KoogProvider.OpenAI
        KoogProvider.Anthropic, KoogProvider.AnthropicCompatible -> KoogProvider.Anthropic
        KoogProvider.AlibabaQwen -> KoogProvider.AlibabaQwen
        KoogProvider.Ollama, null -> null
    }
    return vendor?.takeIf { connection.source.scope.origin == it.origin }
}
