package io.aequicor.heartbeat.feature.aisessionenginetransfer.impl

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aisessionenginetransfer.api.TransferId
import io.aequicor.heartbeat.feature.aisessionenginetransfer.api.TransferRequest

internal val SourceRef = SessionRef(EngineId("codex"), SessionSourceId("codex-local"), "source-session")
internal val TargetRef = SessionRef(EngineId("claude"), SessionSourceId("claude-local"), "target-session")
internal val Target = EngineTarget(TargetRef.engine, EngineBindingId("binding"), ModelId("model"))
internal val Request = TransferRequest(TransferId("transfer"), SourceRef, Target)

internal fun message(position: Long, role: MessageRole, vararg parts: ContentPart) =
    SessionItem.Message(ItemInfo(ItemId("m$position"), position, 0), role, parts.toList())

internal fun text(position: Long, role: MessageRole, text: String) = message(position, role, ContentPart.Text(text))
