package io.aequicor.heartbeat.feature.agentlearning.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.stringKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.agentlearning.api.LearnedInstruction
import io.aequicor.heartbeat.feature.agentlearning.api.LearningApproval
import io.aequicor.heartbeat.feature.agentlearning.impl.domain.LearningStorage
import io.aequicor.heartbeat.feature.agentlearning.impl.domain.StoredLearning
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The registry in the profile key-value store as JSON text, decoded here: an unreadable registry fails the load
 * instead of reading as empty, so the next save cannot replace it. Values are never logged.
 */
@ContributesBinding(ProfileScope::class)
@Inject
internal class KeyValueLearningStorage(
    @ForScope(ProfileScope::class) private val stores: DataStores,
) : LearningStorage {

    private val log = Log.tag("KeyValueLearningStorage")
    private val store by lazy { stores.keyValue(SPEC) }

    override suspend fun load(): StoredLearning {
        val instructions = store.get(INSTRUCTIONS)?.let { json.decodeFromString(INSTRUCTION_LIST, it) }.orEmpty()
        // An unknown approval level falls back to asking, the most careful choice.
        val approval = store.get(APPROVAL)?.let { name -> LearningApproval.entries.firstOrNull { it.name == name } }
        log.d { "read learned instructions: ${instructions.size}, approval known=${approval != null}" }
        return StoredLearning(instructions, approval ?: LearningApproval.Ask)
    }

    override suspend fun saveInstructions(instructions: List<LearnedInstruction>) {
        log.d { "write learned instructions: ${instructions.size}" }
        store.set(INSTRUCTIONS, json.encodeToString(INSTRUCTION_LIST, instructions))
    }

    override suspend fun saveApproval(approval: LearningApproval) {
        log.d { "write learning approval: $approval" }
        store.set(APPROVAL, approval.name)
    }

    private companion object {
        val SPEC = KeyValueSpec("agent_learning")
        val INSTRUCTIONS = stringKey("instructions")
        val APPROVAL = stringKey("approval")
        val INSTRUCTION_LIST = ListSerializer(LearnedInstruction.serializer())
        val json = Json { ignoreUnknownKeys = true }
    }
}
