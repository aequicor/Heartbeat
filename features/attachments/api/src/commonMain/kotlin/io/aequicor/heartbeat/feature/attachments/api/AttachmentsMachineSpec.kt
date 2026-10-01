package io.aequicor.heartbeat.feature.attachments.api

import io.aequicor.heartbeat.core.statemachine.MachineSpec
import io.aequicor.heartbeat.core.statemachine.machineSpec

/**
 * Serializes profile attachment operations, with correlated feedback and recoverable errors.
 *
 * | From | Intent | Guard | To | Effect / output |
 * |---|---|---|---|---|
 * | Idle | Start | | Preparing | Prepare |
 * | Preparing | Prepared / PrepareFailed | | Ready / Error | |
 * | Ready / Error | Choose / Open / Export | | Performing(requestId) | NativeRequested |
 * | Ready / Error | Import | | Performing(requestId) | Import |
 * | Performing(Native) | Selected | matching request | Performing(Importing) | Import |
 * | Performing | Imported / NativeCompleted | matching request | Ready | Imported / Completed |
 * | Performing | Failed | matching request | Error | Failed |
 * | Performing | Cancel | matching request | Ready | Cancelled |
 * | Performing | any new operation or stale feedback | | ignored | |
 *
 * Native operations are owned by the route lifecycle. Import effects never retain UI hosts.
 */
public val AttachmentsMachineSpec:
    MachineSpec<AttachmentsState, AttachmentsIntent, AttachmentsEffect, AttachmentsOutput> =
    machineSpec(AttachmentsMachineKey, AttachmentsState.Idle) {
        state<AttachmentsState.Idle> {
            on<AttachmentsIntent.Public.Start> {
                goto<AttachmentsState.Preparing> { AttachmentsState.Preparing }
                effect { AttachmentsEffect.Prepare }
            }
        }
        state<AttachmentsState.Preparing> {
            on<AttachmentsIntent.Internal.Prepared> { goto<AttachmentsState.Ready> { AttachmentsState.Ready } }
            on<AttachmentsIntent.Internal.PrepareFailed> {
                goto<AttachmentsState.Error> { AttachmentsState.Error(AttachmentFailure.Unavailable) }
            }
        }
        state<AttachmentsState.Ready>()
        state<AttachmentsState.Error>()
        state<AttachmentsState.Performing> {
            on<AttachmentsIntent.Internal.Selected>(
                guard = { intent.requestId == state.requestId && state.phase == AttachmentOperationPhase.Native },
            ) {
                stay { state.copy(phase = AttachmentOperationPhase.Importing) }
                effect { AttachmentsEffect.Import(intent.requestId, intent.inputs, intent.support) }
            }
            on<AttachmentsIntent.Internal.Imported>(
                guard = { intent.requestId == state.requestId && state.phase == AttachmentOperationPhase.Importing },
            ) {
                goto<AttachmentsState.Ready> { AttachmentsState.Ready }
                output { AttachmentsOutput.Imported(intent.requestId, intent.attachments) }
            }
            on<AttachmentsIntent.Internal.NativeCompleted>(
                guard = { intent.requestId == state.requestId && state.phase == AttachmentOperationPhase.Native },
            ) {
                goto<AttachmentsState.Ready> { AttachmentsState.Ready }
                output { AttachmentsOutput.Completed(intent.requestId) }
            }
            on<AttachmentsIntent.Internal.Failed>(guard = { intent.requestId == state.requestId }) {
                goto<AttachmentsState.Error> { AttachmentsState.Error(intent.failure) }
                output { AttachmentsOutput.Failed(intent.requestId, intent.failure) }
            }
            on<AttachmentsIntent.Public.Cancel>(guard = { intent.requestId == state.requestId }) {
                goto<AttachmentsState.Ready> { AttachmentsState.Ready }
                output { AttachmentsOutput.Cancelled(intent.requestId) }
            }
        }
        any {
            on<AttachmentsIntent.Public.Choose>(guard = { state.isAcceptingRequests }) {
                goto<AttachmentsState.Performing> { AttachmentsState.Performing(intent.requestId) }
                output {
                    AttachmentsOutput.NativeRequested(
                        intent.requestId,
                        AttachmentNativeOperation.Choose(intent.support),
                    )
                }
            }
            on<AttachmentsIntent.Public.Import>(guard = { state.isAcceptingRequests }) {
                goto<AttachmentsState.Performing> {
                    AttachmentsState.Performing(
                        intent.requestId,
                        AttachmentOperationPhase.Importing,
                    )
                }
                effect {
                    AttachmentsEffect.Import(
                        intent.requestId,
                        intent.inputs,
                        intent.support,
                        intent.purpose,
                        intent.deduplicationKey,
                    )
                }
            }
            on<AttachmentsIntent.Public.Open>(guard = { state.isAcceptingRequests }) {
                goto<AttachmentsState.Performing> { AttachmentsState.Performing(intent.requestId) }
                output {
                    AttachmentsOutput.NativeRequested(
                        intent.requestId,
                        AttachmentNativeOperation.Open(intent.id),
                    )
                }
            }
            on<AttachmentsIntent.Public.Export>(guard = { state.isAcceptingRequests }) {
                goto<AttachmentsState.Performing> { AttachmentsState.Performing(intent.requestId) }
                output {
                    AttachmentsOutput.NativeRequested(
                        intent.requestId,
                        AttachmentNativeOperation.Export(intent.id),
                    )
                }
            }
        }
        onEffectFailure { effect, _ ->
            when (effect) {
                AttachmentsEffect.Prepare -> AttachmentsIntent.Internal.PrepareFailed

                is AttachmentsEffect.Import -> AttachmentsIntent.Internal.Failed(
                    effect.requestId,
                    AttachmentFailure.Unavailable,
                )
            }
        }
    }

private val AttachmentsState.isAcceptingRequests: Boolean
    get() = this is AttachmentsState.Ready || this is AttachmentsState.Error
