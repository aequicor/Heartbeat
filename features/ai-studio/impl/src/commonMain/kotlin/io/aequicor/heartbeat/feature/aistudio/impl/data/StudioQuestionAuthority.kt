package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermission
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermissionAnswer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Questionnaire answers revoke authority before delivery, using the repository's record and handle ownership. */
internal class StudioQuestionAuthority(
    private val store: KeyValueStore,
    private val records: Mutex,
    private val tools: Lazy<ProfileAgentTools>,
    private val handle: suspend (String) -> ActiveSession?,
) {
    private val log = Log.tag("StudioQuestionAuthority")

    suspend fun respond(id: String, request: String, option: String, answer: StudioPermissionAnswer?) {
        log.v { "Deliver pending native answer with its authority barrier" }
        val active = handle(id)
        active.respondStudioPermission(request, option, answer) { invalidate(id, checkNotNull(active).ref) }
    }

    suspend fun respond(permission: StudioPermission, option: String, answer: StudioPermissionAnswer?) {
        log.v { "Deliver captured native answer with its authority barrier" }
        val active = handle(permission.sessionId)
        active.respondStudioPermission(permission, option, answer) {
            invalidate(permission.sessionId, checkNotNull(active).ref)
        }
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
