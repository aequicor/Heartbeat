package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Routes native questions and approvals with their engine-owned timeout; status notifications need no answer. */
internal suspend fun handlePiUiRequest(
    record: JsonObject,
    turn: TurnId?,
    permissions: PiSessionPermissions,
    approve: suspend (String, String?, Boolean, Long?, Boolean) -> Unit,
) {
    val id = record.string("id") ?: return
    if (record.string("method") !in DIALOG_METHODS) return
    val timeout = (record["timeout"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }
    if (record.string("method") == "confirm" && record.string("title") == APPROVAL_TITLE) {
        approve(id, record.string("message"), false, timeout, false)
    } else if (record.string("method") == "input" && record.string("title") == APPROVAL_TITLE) {
        approve(id, record.string("placeholder"), false, timeout, true)
    } else if (record.string("method") == "input" && record.string("title") == "heartbeat.tool-result") {
        approve(id, record.string("placeholder"), true, timeout, false)
    } else {
        permissions.await(id, turn?.let { PiDialog.from(record, id, it) }, timeout)
    }
}

// Must match APPROVAL_TITLE in resources/pi/heartbeat-approval.ts.
private const val APPROVAL_TITLE = "heartbeat.tool-approval"
private val DIALOG_METHODS = setOf("select", "confirm", "input", "editor")
