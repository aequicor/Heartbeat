package io.aequicor.heartbeat.feature.organicai.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionExecutionBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionExecutionInstance
import io.aequicor.heartbeat.feature.organicai.api.CaseId
import io.aequicor.heartbeat.feature.organicai.api.CellId
import io.aequicor.heartbeat.feature.organicai.api.CellPhase
import io.aequicor.heartbeat.feature.organicai.api.Organism
import io.aequicor.heartbeat.feature.organicai.api.cell
import io.aequicor.heartbeat.feature.organicai.api.isDeveloping
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Unambiguous persisted address, including a nonce independent of reusable organism and cell identifiers. */
internal data class OrganicExecutorAddress(
    val organism: String,
    val incarnation: String,
    val kind: Kind,
    val member: String,
) {
    enum class Kind { Cell, Judge }

    fun instance(): SessionExecutionInstance = SessionExecutionInstance(
        "organic",
        buildJsonObject {
            put("organism", organism)
            put("incarnation", incarnation)
            put("kind", kind.name)
            put("member", member)
        }.toString(),
    )

    companion object {
        fun parse(value: String): OrganicExecutorAddress {
            val fields = Json.parseToJsonElement(value).jsonObject
            require(fields.keys == setOf("organism", "incarnation", "kind", "member")) {
                "Invalid organic execution address"
            }
            fun field(name: String) = fields.getValue(name).jsonPrimitive.content
            return OrganicExecutorAddress(
                field("organism"),
                field("incarnation"),
                Kind.valueOf(field("kind")),
                field("member"),
            )
        }
    }

    fun permits(known: Organism, binding: SessionExecutionBinding): Boolean {
        if (known.id.value != organism || known.incarnation != incarnation || !known.isDeveloping) return false
        return when (kind) {
            Kind.Cell -> {
                val cell = known.cell(CellId(member)) ?: return false
                cell.phase is CellPhase.Working && known.cellExecution(cell.id) == binding
            }

            Kind.Judge -> known.cases.any { it.id == CaseId(member) } && known.judgeExecution(CaseId(member)) == binding
        }
    }
}

/** The full conversation zygote stays human-capable; parentless zygotes are internal executors. */
internal fun Organism.cellExecution(cell: CellId): SessionExecutionBinding? =
    if (cell == CellId.ZYGOTE && conversation != null) {
        null
    } else {
        SessionExecutionBinding(executor(OrganicExecutorAddress.Kind.Cell, cell.value), cellIdentity(cell))
    }

/** A case cannot reopen a judge belonging to an earlier conception with the same display identifier. */
internal fun Organism.judgeExecution(case: CaseId): SessionExecutionBinding =
    SessionExecutionBinding(executor(OrganicExecutorAddress.Kind.Judge, case.value), judgeIdentity(case))

private fun Organism.executor(kind: OrganicExecutorAddress.Kind, member: String): SessionExecutionInstance =
    OrganicExecutorAddress(
        id.value,
        checkNotNull(incarnation) { "Legacy organism has no proven executor incarnation" },
        kind,
        member,
    ).instance()
