package io.aequicor.heartbeat.feature.organicai.impl.data

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.organicai.api.CellId
import io.aequicor.heartbeat.feature.organicai.api.ImmuneCase
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiEnabled
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiIntent
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiState
import io.aequicor.heartbeat.feature.organicai.api.Organism
import io.aequicor.heartbeat.feature.organicai.api.OrganismBounds
import io.aequicor.heartbeat.feature.organicai.api.OrganismTools
import io.aequicor.heartbeat.feature.organicai.api.Refusal
import io.aequicor.heartbeat.feature.organicai.api.complaintRefusal
import io.aequicor.heartbeat.feature.organicai.api.disputeRefusal
import io.aequicor.heartbeat.feature.organicai.api.divisionRefusal
import io.aequicor.heartbeat.feature.organicai.api.isDeveloping
import io.aequicor.heartbeat.feature.organicai.api.nextCaseId
import io.aequicor.heartbeat.feature.organicai.api.nextCellId
import io.aequicor.heartbeat.feature.organicai.impl.domain.OrganicAiMachine
import io.aequicor.heartbeat.feature.organicai.impl.domain.explain
import io.aequicor.heartbeat.feature.organicai.impl.domain.locate
import io.aequicor.heartbeat.feature.organicai.impl.domain.statusReport
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The tools of a cell: divide, complain, dispute and status. They are declared to sessions only while an organism
 * develops, yet a hosted tool cannot be scoped to a session, so every call identifies its cell by the trusted
 * session of the call and refuses anyone else. They only file requests (read-only for the trust gate): results
 * arrive later as letters. Arguments and texts are never logged.
 */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class OrganismAgentTools(
    // Lazy: the machine's effects reach the engine facade, whose hosted tools include these.
    private val machine: Lazy<OrganicAiMachine>,
    private val toggles: FeatureToggles,
) : AgentToolContribution {
    private val log = Log.tag("OrganismAgentTools")

    override val isDetachedSupported: Boolean get() = true

    override suspend fun specifications(workspace: WorkspaceRef?): List<AgentToolSpec> =
        if (toggles.get(OrganicAiEnabled) && living()?.organisms?.values.orEmpty().any { it.isDeveloping }) {
            organismToolSpecs
        } else {
            emptyList()
        }

    override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject): AgentToolResult {
        if (!toggles.get(OrganicAiEnabled)) return failure("organic AI is turned off")
        val living = living() ?: return failure(NOT_A_CELL)
        val address = living.locate(context.session) ?: return failure(NOT_A_CELL)
        val organism = living.organisms.getValue(address.organism)
        return when (name) {
            OrganismTools.DIVIDE -> divide(organism, address.cell, arguments)
            OrganismTools.COMPLAIN -> complain(organism, address.cell, arguments)
            OrganismTools.DISPUTE -> dispute(organism, address.cell, arguments)
            OrganismTools.STATUS -> AgentToolResult(statusReport(organism, address.cell))
            else -> failure("unknown tool")
        }
    }

    private suspend fun divide(organism: Organism, parent: CellId, arguments: JsonObject): AgentToolResult {
        val task = arguments.text(OrganismTools.Arguments.TASK)
            ?.takeIf { it.length <= OrganismBounds.MAX_TASK }
            ?: return failure("give a task of up to ${OrganismBounds.MAX_TASK} characters")
        val child = organism.nextCellId()
        val label = arguments.text(OrganismTools.Arguments.NAME)?.lineSequence()?.first()?.trim()
            ?.take(OrganismBounds.MAX_NAME)
            ?.takeIf(String::isNotBlank)
            ?: "cell ${child.value}"
        organism.divisionRefusal(parent)?.let { return refused(it) }
        val sent = send(OrganicAiIntent.Internal.Divide(organism.id, parent, child, label, task))
        log.i { "organism ${organism.id.value}: ${parent.value} divides into ${child.value}, $sent" }
        return receipt(sent) {
            "Divided: child ${child.value} \"$label\" started on its task. Its result arrives as a message after " +
                "you end your turn; do not wait or poll for it."
        }
    }

    private suspend fun complain(organism: Organism, plaintiff: CellId, arguments: JsonObject): AgentToolResult {
        val accused = arguments.text(OrganismTools.Arguments.CELL)?.let { organism.idOf(it) }
            ?: return refused(Refusal.UnknownCell)
        val reason = arguments.text(OrganismTools.Arguments.REASON)
            ?.takeIf { it.length <= OrganismBounds.MAX_REASON }
            ?: return failure("give the reason in up to ${OrganismBounds.MAX_REASON} characters")
        organism.complaintRefusal(plaintiff, accused)?.let { return refused(it) }
        val case = ImmuneCase.Complaint(organism.nextCaseId(), plaintiff, accused, reason)
        val sent = send(OrganicAiIntent.Internal.Complain(organism.id, case))
        log.i {
            "organism ${organism.id.value}: ${plaintiff.value} accuses ${accused.value} in ${case.id.value}, $sent"
        }
        return receipt(sent) {
            "Complaint ${case.id.value} against ${accused.value} filed. A fresh immune session judges it; " +
                "the verdict arrives as a message after you end your turn."
        }
    }

    private suspend fun dispute(organism: Organism, asker: CellId, arguments: JsonObject): AgentToolResult {
        val question = arguments.text(OrganismTools.Arguments.QUESTION)
            ?.takeIf { it.length <= OrganismBounds.MAX_QUESTION }
            ?: return failure("give the question in up to ${OrganismBounds.MAX_QUESTION} characters")
        val named = (arguments[OrganismTools.Arguments.PARTIES] as? JsonArray).orEmpty()
            .map { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content?.trim().orEmpty() }
        val parties = named.map { organism.idOf(it) ?: return refused(Refusal.UnknownCell) }
        organism.disputeRefusal(asker, parties)?.let { return refused(it) }
        val case = ImmuneCase.Dispute(organism.nextCaseId(), asker, question, parties)
        val sent = send(OrganicAiIntent.Internal.Dispute(organism.id, case))
        log.i { "organism ${organism.id.value}: ${asker.value} opens dispute ${case.id.value}, $sent" }
        return receipt(sent) {
            "Dispute ${case.id.value} filed. The binding answer arrives as a message after you end your turn; end " +
                "your turn now if you cannot continue without it."
        }
    }

    private fun living(): OrganicAiState.Living? = machine.value.state.value as? OrganicAiState.Living

    private suspend fun send(intent: OrganicAiIntent): SendResult = machine.value.send(intent)

    private fun receipt(sent: SendResult, text: () -> String): AgentToolResult =
        if (sent == SendResult.Accepted) AgentToolResult(text()) else failure("the organism changed meanwhile; retry")

    private fun refused(refusal: Refusal) = failure("refused: ${refusal.explain()}")

    private fun failure(message: String) = AgentToolResult(message.replaceFirstChar(Char::uppercase), isError = true)

    private fun Organism.idOf(text: String): CellId? = cells.firstOrNull { it.id.value == text.trim() }?.id

    private fun JsonObject.text(name: String): String? =
        (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf(String::isNotEmpty)

    private companion object {
        const val NOT_A_CELL = "only cells of a developing organic AI organism can use this tool"
    }
}
