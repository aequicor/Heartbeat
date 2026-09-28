package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailure
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailureReason
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.CompatibleProtocol
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineContext
import io.aequicor.heartbeat.feature.aiengine.pi.api.PiEngineId

/**
 * Pi routes. Vendor routes have a fixed [origin]; [compatible] routes take the origin of the source scope and are
 * declared to Pi through a per-process `models.json`, whose key is read from [variable].
 */
internal enum class PiProvider(
    val id: String,
    val origin: String?,
    val variable: String,
    val compatible: CompatibleProtocol? = null,
) {
    Anthropic("anthropic", "https://api.anthropic.com", "ANTHROPIC_API_KEY"),
    OpenAi("openai", "https://api.openai.com", "OPENAI_API_KEY"),
    Google("google", "https://generativelanguage.googleapis.com", "GEMINI_API_KEY"),
    OpenAiCompatible(
        CompatibleProtocol.OpenAI.provider.id.value,
        null,
        COMPATIBLE_KEY_VARIABLE,
        CompatibleProtocol.OpenAI,
    ),
    AnthropicCompatible(
        CompatibleProtocol.Anthropic.provider.id.value,
        null,
        COMPATIBLE_KEY_VARIABLE,
        CompatibleProtocol.Anthropic,
    ),
}

internal const val COMPATIBLE_KEY_VARIABLE = "HEARTBEAT_PROVIDER_API_KEY"

internal fun provider(source: AuthSource): PiProvider? = PiProvider.entries.firstOrNull {
    it.id == source.scope.provider.value &&
        if (it.compatible != null) {
            CompatibleProtocol.of(source.scope) == it.compatible
        } else {
            it.origin == source.scope.origin.value && source.scope.basePath == null
        }
}

internal fun acceptsPi(source: AuthSource, context: EngineContext): Boolean =
    context.engine == PiEngineId && source is AuthSource.ManagedKey &&
        source.secret.value.matches(Regex("[a-zA-Z0-9_-]{1,128}")) && provider(source) != null &&
        (context.model?.value?.startsWith(source.scope.provider.value + "/") != false)

internal fun authenticationFailure(reason: AuthFailureReason, source: AuthSourceId): Nothing =
    piFailure(EngineFailure.Authentication(AuthFailure(reason, source)))

internal fun piFailure(failure: EngineFailure): Nothing = throw EngineException(failure)
