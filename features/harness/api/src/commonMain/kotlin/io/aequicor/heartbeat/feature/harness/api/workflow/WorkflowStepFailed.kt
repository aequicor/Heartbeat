package io.aequicor.heartbeat.feature.harness.api.workflow

/** Stable replay failure, without author exception messages or causes. Workflows may catch expected step failures. */
public class WorkflowStepFailed(public val reason: WorkflowFailure) : Exception(reason.name)
