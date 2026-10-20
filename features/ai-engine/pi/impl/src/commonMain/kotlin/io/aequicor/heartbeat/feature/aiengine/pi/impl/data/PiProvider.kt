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

/** Global Qwen Token Plan endpoint; the individual plan serves its models from the same address. */
internal const val QWEN_TOKEN_PLAN_ORIGIN = "https://token-plan.ap-southeast-1.maas.aliyuncs.com"

/** Qwen Token Plan endpoint of the China region. */
internal const val QWEN_TOKEN_PLAN_CN_ORIGIN = "https://token-plan.cn-beijing.maas.aliyuncs.com"

/** API path Pi's catalog serves Qwen Token Plan models under; the origin root is not a catalog endpoint. */
internal const val QWEN_TOKEN_PLAN_API = "/compatible-mode/v1"

/**
 * Pi routes. Vendor routes have a fixed [origin]; [compatible] routes take the origin of the source scope and are
 * declared to Pi through a per-process `models.json`, whose key is read from [variable].
 * A vendor route whose endpoint is not the origin root pins [pinnedBaseUrl]: Pi keeps its own catalog for the route,
 * so model ids, context limits, thinking levels and input modalities stay the ones Pi verified for that endpoint.
 */
internal enum class PiProvider(
    val id: String,
    val origin: String?,
    val variable: String,
    val compatible: CompatibleProtocol? = null,
    val pinnedBaseUrl: String? = null,
) {
    Anthropic("anthropic", "https://api.anthropic.com", "ANTHROPIC_API_KEY"),
    OpenAi("openai", "https://api.openai.com", "OPENAI_API_KEY"),
    Google("google", "https://generativelanguage.googleapis.com", "GEMINI_API_KEY"),
    ZaiCodingPlan("zai", "https://api.z.ai", "ZAI_API_KEY", pinnedBaseUrl = "https://api.z.ai/api/coding/paas/v4"),
    QwenTokenPlan(
        "qwen-token-plan",
        QWEN_TOKEN_PLAN_ORIGIN,
        "QWEN_TOKEN_PLAN_API_KEY",
        pinnedBaseUrl = QWEN_TOKEN_PLAN_ORIGIN + QWEN_TOKEN_PLAN_API,
    ),
    QwenTokenPlanIndividual(
        "qwen-token-plan-individual",
        QWEN_TOKEN_PLAN_ORIGIN,
        "QWEN_TOKEN_PLAN_API_KEY",
        pinnedBaseUrl = QWEN_TOKEN_PLAN_ORIGIN + QWEN_TOKEN_PLAN_API,
    ),
    QwenTokenPlanCn(
        "qwen-token-plan-cn",
        QWEN_TOKEN_PLAN_CN_ORIGIN,
        "QWEN_TOKEN_PLAN_CN_API_KEY",
        pinnedBaseUrl = QWEN_TOKEN_PLAN_CN_ORIGIN + QWEN_TOKEN_PLAN_API,
    ),
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
