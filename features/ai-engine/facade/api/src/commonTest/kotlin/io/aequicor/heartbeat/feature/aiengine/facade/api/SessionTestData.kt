package io.aequicor.heartbeat.feature.aiengine.facade.api

internal val TestTarget = EngineTarget(EngineId("codex"), EngineBindingId("binding"), ModelId("model"))
internal val TestPrompt = PromptRequest(RequestId("request"), listOf(ContentPart.Text("private prompt")))
internal val TestTurn = Turn(TurnId("turn"), TestPrompt.id, TestTarget)
internal val TestPermission = PermissionRequest(
    PermissionRequestId("permission"),
    TestTurn.id,
    "Run tool?",
    listOf(
        PermissionOption(PermissionOptionId("allow"), "Allow"),
        PermissionOption(PermissionOptionId("deny"), "Deny"),
    ),
)
