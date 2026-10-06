package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.spi.HelperCreateRequest
import io.aequicor.heartbeat.feature.scheduler.api.spi.HelperMetadata
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Empty helper creation and identity lookup never open a native session. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class EngineStudioHelperChatRecords(
    @ForScope(ProfileScope::class) stores: DataStores,
    private val writer: StudioHelperChatWriter,
    private val workspaces: LocalWorkspaces,
    private val clock: Clock,
) : StudioHelperChatRecords {
    private val log = Log.tag("EngineStudioHelperChatRecords")
    private val store = stores.keyValue(ChatSpec)

    override suspend fun createHelper(request: HelperCreateRequest): HelperId {
        val parent = request.parent?.let { session ->
            checkNotNull(store.get(ChatsKey).orEmpty().singleOrNull { it.ref == session }) {
                "The helper parent must identify exactly one studio conversation"
            }
        }
        val record = newStudioHelperRecord(Uuid.random().toString(), clock.now(), request, parent)
        record.executionWorkspace?.let { workspace ->
            checkNotNull(workspaces.resolve(workspace)) { "The helper workspace is unavailable" }
        }
        // One write persists the empty chat, route, owner and parent before exposing its id. No native IO.
        writer.saveHelper(record)
        log.i { "Created empty helper conversation hasParent=${parent != null}" }
        return HelperId(record.id)
    }

    override suspend fun helperMetadata(helper: HelperId): HelperMetadata? {
        log.v { "Read durable helper identity" }
        val record = store.get(ChatsKey).orEmpty().firstOrNull { it.id == helper.value } ?: return null
        return record.helper?.let { identity ->
            HelperMetadata(helper, identity.owner, identity.parentSession, record.ref, record.lastRunRequest)
        }
    }

    override suspend fun isHelper(session: SessionRef): Boolean {
        log.v { "Read durable helper marker" }
        return store.get(ChatsKey).orEmpty().any { it.ref == session && it.helper != null }
    }
}
