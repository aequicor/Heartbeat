package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioMessage
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRepository
import kotlin.uuid.Uuid

/** Native engines own their transcript and branch mutations; scripted repositories supply local writers. */
internal abstract class NativeStudioRepository : StudioRepository {
    private val log = Log.tag("NativeStudioRepository")
    override suspend fun defaultProjectId(): String? {
        log.d { "Read default workspace" }
        return null
    }

    override fun newMessageId(): String = Uuid.random().toString().also { log.d { "Allocate message identity" } }
    override suspend fun append(sessionId: String, message: StudioMessage) {
        log.w { "Rejected local transcript append" }
        error("Native history owns transcript writes")
    }

    override suspend fun replace(sessionId: String, message: StudioMessage) {
        log.w { "Rejected local transcript replacement" }
        error("Native history owns transcript writes")
    }

    override suspend fun setBranch(sessionId: String, branch: String) {
        log.w { "Rejected local branch mutation" }
        error("Native tools own branch metadata")
    }
}
