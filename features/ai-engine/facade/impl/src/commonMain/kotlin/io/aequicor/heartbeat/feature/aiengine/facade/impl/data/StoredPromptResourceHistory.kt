package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.jsonKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptResourceHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8

private val PromptSourcesSpec = KeyValueSpec("ai_prompt_resource_history")
private const val MAX_RESOURCES = 10
private const val MAX_REFERENCE_LENGTH = 256
private const val MAX_TEXT_LENGTH = 1_048_576L

/** Profile KV logs only hashed keys; original attachment IDs never become temporary native paths. */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
@Inject
internal class StoredPromptResourceHistory(
    @ForScope(ProfileScope::class) stores: DataStores,
) : PromptResourceHistory {
    private val store = stores.keyValue(PromptSourcesSpec)

    override suspend fun remember(session: SessionRef, nativeId: String, parts: List<ContentPart>) {
        val resources = parts.mapNotNull {
            when (it) {
                is ContentPart.Image -> it.resource
                is ContentPart.Resource -> it.resource
                is ContentPart.Text, is ContentPart.Reasoning -> null
            }
        }
        if (resources.isEmpty()) return
        require(
            resources.size <= MAX_RESOURCES && resources.all {
                it.id.startsWith("attachment:") && it.id.length <= MAX_REFERENCE_LENGTH
            },
        )
        require(parts.filterIsInstance<ContentPart.Text>().sumOf { it.text.length.toLong() } <= MAX_TEXT_LENGTH)
        store.set(key(session, nativeId), parts)
    }

    override suspend fun parts(session: SessionRef, nativeId: String): List<ContentPart>? =
        store.get(key(session, nativeId))

    private fun key(session: SessionRef, nativeId: String) = jsonKey(
        "source_" + (
            Json.encodeToString(
                SessionRef.serializer(),
                session,
            ) + "\n" + nativeId
        ).encodeUtf8().sha256().hex(),
        ListSerializer(ContentPart.serializer()),
    )
}
