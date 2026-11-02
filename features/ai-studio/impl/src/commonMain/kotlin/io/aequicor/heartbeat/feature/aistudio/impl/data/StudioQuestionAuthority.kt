package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOptionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.RelaysPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermission
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermissionAnswer
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Questionnaire answers revoke authority before delivery, using the repository's record and handle ownership. */
@SingleIn(ProfileScope::class)
internal class StudioQuestionAuthority(
    private val store: KeyValueStore,
    private val records: Mutex,
    private val tools: Lazy<ProfileAgentTools>,
    private val organic: Lazy<StudioOrganicPermissions>? = null,
    private val handle: suspend (String) -> ActiveSession?,
) {
    @Inject
    constructor(
        records: StudioChatRecords,
        tools: Lazy<ProfileAgentTools>,
        access: Lazy<StudioHelperAccess>,
        organic: Lazy<StudioOrganicPermissions>,
    ) : this(records.store, records.lock, tools, organic, { access.value.liveHelper(HelperId(it)) })

    private val log = Log.tag("StudioQuestionAuthority")

    suspend fun respond(
        pending: List<StudioPermission>,
        id: String,
        request: String,
        option: String,
        answer: StudioPermissionAnswer?,
    ) {
        val captured = pending.singleOrNull { it.sessionId == id && it.requestId == request }
        if (captured?.relay != null) respond(captured, option, answer) else respond(id, request, option, answer)
    }

    suspend fun respond(id: String, request: String, option: String, answer: StudioPermissionAnswer?) {
        log.v { "Deliver pending native answer with its authority barrier" }
        requireHumanConversation(id)
        val active = handle(id)
        active.respondStudioPermission(request, option, answer) { invalidate(id, checkNotNull(active).ref) }
    }

    suspend fun respond(permission: StudioPermission, option: String, answer: StudioPermissionAnswer?) {
        log.v { "Deliver captured native answer with its authority barrier" }
        requireHumanConversation(permission.sessionId)
        if (permission.relay != null) {
            respondMediated(permission, option, answer)
            return
        }
        val active = handle(permission.sessionId)
        active.respondStudioPermission(permission, option, answer) {
            invalidate(permission.sessionId, checkNotNull(active).ref)
        }
    }

    private suspend fun respondMediated(permission: StudioPermission, option: String, answer: StudioPermissionAnswer?) {
        val relay = checkNotNull(permission.relay)
        check(relay.owner.value == permission.sessionId && relay.nonce == permission.requestId)
        val decision = PermissionDecision(
            relay.permission.turn,
            relay.permission.id,
            PermissionOptionId(option),
            answer?.toFacade(),
        )
        check(relay.accepts(decision)) { "Decision exceeds the exact executor operation" }
        if (relay.binding.instance.provider == "organic") {
            if (answer != null || relay.permission.input != null) tools.value.invalidateUserInstructions(relay.session)
            checkNotNull(organic).value.respond(relay, decision)
            return
        }
        val child = checkNotNull(relay.binding.logicalSession).id.value
        val active = checkNotNull(handle(child)) { "Executor permission handle is unavailable" }
        check(active.ref == relay.session)
        if (answer != null || relay.permission.input != null) invalidate(child, relay.session)
        currentCoroutineContext().ensureActive()
        deliverStudioPermission(active.state, decision) {
            active.features.requireFeature(RelaysPermissions).respond(relay, decision)
        }
    }

    private suspend fun requireHumanConversation(id: String) = records.withLock {
        resolvedStudioIdentities(store.get(ChatsKey).orEmpty()).singleOrNull { it.id == id }
            ?.requireHumanConversation() ?: error("Unknown studio conversation")
    }

    /** Durable revocation precedes the live barrier; no repository mutex is held while cancelling evaluations. */
    suspend fun invalidate(id: String, native: SessionRef? = null) {
        log.i { "Revoke human authority before delivering questionnaire data" }
        val recorded = records.withLock { invalidateStudioUserInstructions(store, id) }
        currentCoroutineContext().ensureActive()
        val current = handle(id)?.ref
        listOfNotNull(native, recorded, current).distinct().forEach { tools.value.invalidateUserInstructions(it) }
        currentCoroutineContext().ensureActive()
    }
}
