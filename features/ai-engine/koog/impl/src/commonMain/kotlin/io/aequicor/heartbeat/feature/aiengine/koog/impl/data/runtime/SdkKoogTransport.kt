package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicClientSettings
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.executor.ollama.client.toLLModel
import ai.koog.prompt.llm.LLMProvider
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.HostPlatform
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthScope
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
import io.aequicor.heartbeat.feature.aiengine.facade.api.CompatibleProtocol
import io.aequicor.heartbeat.feature.aiengine.facade.api.isCompatibleOriginAllowed
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogProvider
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestRetry

@ContributesBinding(ProfileScope::class)
@Inject
internal class SdkKoogTransport(private val httpClient: HttpClient, private val platform: PlatformInfo) :
    KoogTransport {
    private val log = Log.tag("KoogTransport")

    override fun open(
        provider: KoogProvider,
        key: String?,
        model: String?,
        origin: EndpointOrigin,
        basePath: String?,
    ): KoogClient = create(provider, key, model, origin, basePath)

    override fun openIsolated(
        key: String?,
        origin: EndpointOrigin,
        basePath: String?,
        request: IsolatedKoogRequest,
    ): KoogClient? {
        // The JVM SLF4J payload guard is verified. Native/Android SDK logging needs its own proof before opt-in.
        if (platform.host == HostPlatform.Ios || platform.host == HostPlatform.Android) return null
        return create(request.provider, key, request.model, origin, basePath, request)
    }

    private fun create(
        provider: KoogProvider,
        key: String?,
        model: String?,
        origin: EndpointOrigin,
        basePath: String?,
        isolated: IsolatedKoogRequest? = null,
    ): KoogClient {
        log.d { "Creating ${provider.name} transport" }
        // The origin is passed explicitly, so credentials follow the source scope rather than SDK defaults.
        require(if (provider.isOriginEditable) isCompatibleOriginAllowed(origin) else origin == provider.origin) {
            "Origin ${origin.value} is not allowed for ${provider.name}"
        }
        require(provider.isOriginEditable || basePath == null) { "${provider.name} has a fixed API path" }
        val paths = KoogPaths(provider, AuthScope(provider.id, origin, basePath))
        // Derived clients retain core network logging. Redirects cannot forward provider credentials.
        val base = httpClient.config {
            followRedirects = false
            if (isolated != null) {
                install(HttpRequestRetry) { maxRetries = 0 }
                install(isolatedKoogWire(isolated))
            }
        }
        isolated?.lease?.add(base)
        val usage = KoogUsageCapture()
        val ollama = if (isolated != null && provider == KoogProvider.Ollama) IsolatedOllamaCompletion() else null
        val owned = isolated?.let {
            OwnedKoogHttpFactory(
                KtorKoogHttpClient.Factory(baseClient = base),
                it.lease,
                ollama,
            )
        }
        val sdkFactory = owned ?: KtorKoogHttpClient.Factory(baseClient = base)
        val factory = sdkFactory.withUsage(provider, usage)
        val client = try {
            sdkClient(provider, key, model, paths, factory)
        } finally {
            // SDK clients own a configured child; release the intermediate configuration on every path.
            base.close()
        }
        val executor = MultiLLMPromptExecutor(mapOf(provider.llmProvider to client))
        val reasoning: suspend (List<String>) -> Map<String, List<String>>? = { models ->
            probeReasoning(httpClient, provider, key, models)
        }
        return KoogClient(
            executor,
            reasoning,
            usage,
            awaitClosed = { isolated?.lease?.closeAndAwait() },
            observedFinishReason = { ollama?.reason },
        ) {
            if (client is OllamaClient) client.getModels().map { it.toLLModel() } else client.models()
        }
    }
}

internal val KoogProvider.llmProvider: LLMProvider
    get() = when (this) {
        KoogProvider.OpenAI, KoogProvider.AlibabaQwen, KoogProvider.OpenAICompatible -> LLMProvider.OpenAI
        KoogProvider.Anthropic, KoogProvider.AnthropicCompatible -> LLMProvider.Anthropic
        KoogProvider.Ollama -> LLMProvider.Ollama
    }

/** SDK-relative request paths; compatible routes use the user's base path, vendor routes the SDK defaults. */
private class KoogPaths(provider: KoogProvider, scope: AuthScope) {
    val baseUrl: String = scope.origin.value
    private val openAiPrefix =
        if (provider == KoogProvider.OpenAICompatible) CompatibleProtocol.OpenAI.apiBase(scope) else "/v1"
    private val anthropicPrefix =
        if (provider == KoogProvider.AnthropicCompatible) CompatibleProtocol.Anthropic.apiBase(scope) else ""

    fun openAi(endpoint: String): String = "$openAiPrefix/$endpoint".trimStart('/')

    fun anthropic(endpoint: String): String = "$anthropicPrefix/v1/$endpoint".trimStart('/')

    /** Maps [model] to itself so arbitrary provider ids pass the SDK's model version lookup. */
    fun anthropicSettings(baseUrl: String, provider: KoogProvider, model: String?): AnthropicClientSettings =
        AnthropicClientSettings(
            modelVersionsMap = model?.let {
                mapOf(provider.textModel(it) to it, provider.textModel(it, attachments = true) to it)
            } ?: AnthropicClientSettings().modelVersionsMap,
            baseUrl = baseUrl,
            messagesPath = anthropic("messages"),
            modelsPath = anthropic("models"),
        )
}

private fun KoogHttpClient.Factory.withUsage(provider: KoogProvider, usage: KoogUsageCapture): KoogHttpClient.Factory =
    if (provider.llmProvider == LLMProvider.Anthropic) UsageKoogHttpFactory(this, usage) else this

private fun sdkClient(
    provider: KoogProvider,
    key: String?,
    model: String?,
    paths: KoogPaths,
    factory: KoogHttpClient.Factory,
): LLMClient = when (provider) {
    KoogProvider.OpenAI, KoogProvider.OpenAICompatible -> OpenAILLMClient(
        apiKey = requireNotNull(key),
        settings = OpenAIClientSettings(
            baseUrl = paths.baseUrl,
            chatCompletionsPath = paths.openAi("chat/completions"),
            modelsPath = paths.openAi("models"),
        ),
        httpClientFactory = factory,
    )

    KoogProvider.AlibabaQwen -> AlibabaKoogClient(
        apiKey = requireNotNull(key),
        settings = OpenAIClientSettings(
            baseUrl = paths.baseUrl,
            chatCompletionsPath = "compatible-mode/v1/chat/completions",
            modelsPath = "compatible-mode/v1/models",
        ),
        httpClientFactory = factory,
    )

    KoogProvider.Anthropic, KoogProvider.AnthropicCompatible -> AnthropicLLMClient(
        apiKey = requireNotNull(key),
        settings = paths.anthropicSettings(paths.baseUrl, provider, model),
        httpClientFactory = factory,
    )

    KoogProvider.Ollama -> OllamaClient(httpClientFactory = factory, baseUrl = paths.baseUrl)
}
