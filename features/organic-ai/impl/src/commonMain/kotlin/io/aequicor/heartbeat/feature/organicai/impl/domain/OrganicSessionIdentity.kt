package io.aequicor.heartbeat.feature.organicai.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.LogicalSessionContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.LogicalSessionId
import io.aequicor.heartbeat.feature.organicai.api.CaseId
import io.aequicor.heartbeat.feature.organicai.api.CellId
import io.aequicor.heartbeat.feature.organicai.api.Organism

/** The zygote keeps its full chat identity; every descendant belongs to that same chat, not its biological parent. */
internal fun Organism.cellIdentity(cell: CellId): LogicalSessionContext? {
    val owner = conversation ?: return null
    return if (cell == CellId.ZYGOTE) {
        owner
    } else {
        owner.subagent(
            LogicalSessionId("organic:${id.value}:cell:${cell.value}"),
        )
    }
}

/** Each case owns a separate executor identity under the original full conversation. */
internal fun Organism.judgeIdentity(case: CaseId): LogicalSessionContext? =
    conversation?.subagent(LogicalSessionId("organic:${id.value}:judge:${case.value}"))
