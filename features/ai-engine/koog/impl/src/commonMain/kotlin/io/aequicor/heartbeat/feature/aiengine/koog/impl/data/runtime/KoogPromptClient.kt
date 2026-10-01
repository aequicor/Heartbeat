package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.koog.api.koogProvider

/** Provider transport opens only after host data and serializer formats have passed bounded validation. */
internal suspend fun KoogAccess.preparePromptClient(
    connection: io.aequicor.heartbeat.feature.aiengine.koog.api.KoogConnection,
    model: String,
    request: PromptRequest,
    history: List<List<ContentPart>>,
    closeClient: (KoogClient) -> Unit,
): KoogClient {
    val access = this
    val provider = requireNotNull(koogProvider(connection.source))
    // Validate references before opening a provider. Route-specific modalities are negotiated below.
    val references = resolveKoogInputs(
        request.parts,
        providerInputSupport(
            provider,
            io.aequicor.heartbeat.feature.aiengine.facade.api.PromptInputSupport(
                imageMediaTypes = setOf("image/png", "image/jpeg", "image/webp", "image/gif"),
                resourceMediaTypes = setOf("text/plain", "text/markdown", "application/pdf"),
            ),
        ),
        access.resources,
        request.id,
    )
    references.koogUserParts(provider, request.id)
    val client = koogCall { access.open(connection, model) }
    val budget = KoogEncodedRequestBudget(provider, request.id)
    var isValidated = false
    try {
        if ((listOf(request.parts) + history).flatten()
                .any { it is ContentPart.Image || it is ContentPart.Resource }
        ) {
            val discovered = client.models().firstOrNull { it.id == model }
            if (discovered != null) {
                access.inputs.remember(connection, discovered)
            } else {
                access.inputs.forget(connection, model)
            }
            // Preserve provenance for quotas: resolved TXT/MD data URIs are transport data, not inline context.
            (history + listOf(request.parts)).forEach { parts ->
                val native = resolveKoogInputs(
                    parts,
                    access.inputs.cached(connection, model),
                    access.resources,
                    request.id,
                )
                    .koogUserParts(provider, request.id)
                budget.append(native)
            }
        } else {
            (history + listOf(request.parts)).forEach { budget.append(it.koogUserParts(provider, request.id)) }
        }
        isValidated = true
    } finally {
        if (!isValidated) closeClient(client)
    }
    return client
}
