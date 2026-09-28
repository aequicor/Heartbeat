package io.aequicor.heartbeat.feature.researchchat.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.researchchat.api.ResearchChatIntent
import io.aequicor.heartbeat.feature.researchchat.api.ResearchSession
import io.aequicor.heartbeat.feature.researchchat.api.ResearchWorkspace
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow

/** Durable research data and profile-owned execution, shared by all research screens. */
internal interface ResearchRepository {
    fun observe(): Flow<ResearchWorkspace>
    suspend fun prepare(target: EngineTarget): ResearchWorkspace
    suspend fun createSession(target: EngineTarget): ResearchSession
    suspend fun createQuestion(sessionId: String): String
    suspend fun addResource(sessionId: String, questionId: String, input: ResearchChatIntent.Public.AddResource)
    suspend fun setResourceSelected(sessionId: String, questionId: String, resourceId: String, isSelected: Boolean)
    suspend fun shareResource(sessionId: String, resourceId: String)
    suspend fun removeResource(sessionId: String, resourceId: String)
    suspend fun run(sessionId: String, questionId: String, prompt: String): ResearchRun
    suspend fun stop(questionId: String)
}

/** Awaiting either acknowledgement never owns or cancels the profile job. */
internal data class ResearchRun(val accepted: Deferred<Boolean>, val completion: Deferred<Boolean>)

/** Serialized profile storage; source and transcript values never appear in diagnostics. */
internal interface ResearchStorage {
    fun observe(): Flow<List<ResearchSession>>
    suspend fun read(): List<ResearchSession>
    suspend fun add(session: ResearchSession)
    suspend fun update(id: String, transform: (ResearchSession) -> ResearchSession)
}
