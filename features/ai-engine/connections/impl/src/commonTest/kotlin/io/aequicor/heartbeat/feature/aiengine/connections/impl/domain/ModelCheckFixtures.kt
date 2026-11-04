package io.aequicor.heartbeat.feature.aiengine.connections.impl.domain

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthScope
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceInfo
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.ProviderId
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelCheckGeneration
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelCheckReport
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelCheckRevision
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import kotlin.time.Instant

internal val CheckSource = AuthSource.NoAuth(
    AuthSourceInfo(AuthSourceId("check-source"), "", AuthRevision.Known("revision-1")),
    AuthScope(ProviderId("provider"), EndpointOrigin("https://provider.example")),
)

internal fun checkReport(model: String = "model", isWarning: Boolean = false): ModelCheckReport = ModelCheckReport(
    revision = ModelCheckRevision(
        EngineTarget(EngineId("engine"), EngineBindingId("binding"), ModelId(model)),
        ModelCheckGeneration("11111111-1111-1111-1111-111111111111"),
        CheckSource,
        ModelCheckRevision.CURRENT_DEFINITION,
    ),
    run = ModelCheckGeneration("22222222-2222-2222-2222-222222222222"),
    checkedAt = Instant.fromEpochSeconds(0),
    response = ModelCheckEvaluation.response("READY"),
    accuracy = ModelCheckEvaluation.accuracy(if (isWarning) "18|cedar|ABSENT" else "19|cedar|ABSENT"),
)
