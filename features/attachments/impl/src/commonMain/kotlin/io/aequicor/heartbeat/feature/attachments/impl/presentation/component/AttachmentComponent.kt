package io.aequicor.heartbeat.feature.attachments.impl.presentation.component

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.instancekeeper.InstanceKeeper
import com.arkivanov.essenty.instancekeeper.getOrCreate
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeFactory
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.ext.retainedScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.navigation.Navigator
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.core.statemachine.flowmvi.reflect
import io.aequicor.heartbeat.core.statemachine.flowmvi.sendTo
import io.aequicor.heartbeat.feature.attachments.api.AttachmentFailure
import io.aequicor.heartbeat.feature.attachments.api.AttachmentNativeOperation
import io.aequicor.heartbeat.feature.attachments.api.AttachmentPreviewRoute
import io.aequicor.heartbeat.feature.attachments.api.AttachmentSelection
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsIntent
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsOutput
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsPickRoute
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsPicked
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsState
import io.aequicor.heartbeat.feature.attachments.impl.domain.AttachmentNativeActions
import io.aequicor.heartbeat.feature.attachments.impl.domain.AttachmentStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState
import pro.respawn.flowmvi.api.Store
import pro.respawn.flowmvi.plugins.reduce
import kotlin.uuid.Uuid

/** Transient preview state; bytes are screen-owned and never serialized or logged. */
internal data class AttachmentScreenState(
    val name: String = "",
    val bytes: ByteArray? = null,
    val text: String? = null,
    val isTextTruncated: Boolean = false,
    val isImage: Boolean = false,
    val isPicking: Boolean = false,
    val isLoading: Boolean = true,
    val isBusy: Boolean = false,
    val hasError: Boolean = false,
) : MVIState {
    override fun toString(): String = "AttachmentScreenState(loading=$isLoading, error=$hasError)"
}

internal sealed interface AttachmentScreenIntent : MVIIntent {
    data object Open : AttachmentScreenIntent
    data object Export : AttachmentScreenIntent
    data class Loaded(
        val name: String,
        val bytes: ByteArray?,
        val text: String?,
        val isImage: Boolean,
        val isTextTruncated: Boolean = false,
    ) : AttachmentScreenIntent {
        override fun toString(): String = "AttachmentScreenIntent.Loaded(redacted)"
    }
    data object Failed : AttachmentScreenIntent
}

internal sealed interface AttachmentScreenAction : MVIAction

/** Native operations are scoped to [runNativeHost], never stored in the profile dependency graph. */
@AssistedInject
internal class AttachmentComponent(
    @Assisted context: ComponentContext,
    @Assisted private val navigator: Navigator,
    @Assisted private val pick: AttachmentsPickRoute?,
    @Assisted private val preview: AttachmentPreviewRoute?,
    scopes: ScopeFactory,
    @ForScope(ProfileScope::class) profile: ScopeHandle,
    private val machine: Machine<AttachmentsState, AttachmentsIntent, AttachmentsOutput>,
    private val storage: AttachmentStorage,
    private val native: AttachmentNativeActions,
    factory: HeartbeatStoreFactory,
) : ComponentContext by context {
    private val log = Log.tag("AttachmentComponent")
    private val scope = context.retainedScope(scopes, profile, "attachment-screen")
    private val operationPrefix = context.instanceKeeper.getOrCreate("attachment-operation-identity") {
        AttachmentOperationIdentity(Uuid.random().toString())
    }.value

    private val retainedStore = context.instanceKeeper.getOrCreate("attachment-store") {
        AttachmentStoreHolder(
            factory.create<AttachmentScreenState, AttachmentScreenIntent, AttachmentScreenAction>(
                "AttachmentPreview",
                AttachmentScreenState(isPicking = pick != null),
                onError = { copy(isLoading = false, hasError = true) },
            ) {
                reflect(machine) { copy(isBusy = it is AttachmentsState.Performing) }
                reduce { intent ->
                    when (intent) {
                        AttachmentScreenIntent.Open -> preview?.let {
                            sendTo(machine, AttachmentsIntent.Public.Open("$operationPrefix:open", it.id))
                        }

                        AttachmentScreenIntent.Export -> preview?.let {
                            sendTo(machine, AttachmentsIntent.Public.Export("$operationPrefix:export", it.id))
                        }

                        is AttachmentScreenIntent.Loaded -> updateState {
                            copy(
                                name = intent.name,
                                bytes = intent.bytes,
                                text = intent.text,
                                isTextTruncated = intent.isTextTruncated,
                                isImage = intent.isImage,
                                isLoading = false,
                            )
                        }

                        AttachmentScreenIntent.Failed -> updateState { copy(isLoading = false, hasError = true) }
                    }
                }
            }
                .also { it.start(scope.coroutineScope) },
        )
    }

    val store: Store<AttachmentScreenState, AttachmentScreenIntent, AttachmentScreenAction> = retainedStore.store

    init {
        preview?.let { route ->
            scope.coroutineScope.launch {
                try {
                    val file = storage.read(route.id)
                    val text = file.bytes.takeIf { file.mediaType.startsWith("text/") }?.toTextPreview()
                    store.intent(
                        AttachmentScreenIntent.Loaded(
                            file.name,
                            file.bytes.takeIf { file.mediaType.startsWith("image/") },
                            text?.text,
                            file.mediaType.startsWith("image/"),
                            text?.isTruncated == true,
                        ),
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    log.w(safeError(error)) { "Saved attachment cannot be previewed" }
                    store.intent(AttachmentScreenIntent.Failed)
                }
            }
        }
    }

    /** Runs under the current composition lifecycle; cancellation drops its host and cancels only its own request. */
    suspend fun runNativeHost(host: Any?): Unit = coroutineScope {
        val listener = launch(start = CoroutineStart.UNDISPATCHED) {
            machine.outputs.collect { handleOutput(host, it) }
        }
        try {
            startNativeRequest()
            listener.join()
        } finally {
            listener.cancel()
            withContext(NonCancellable) { cancelOwnedRequest() }
        }
    }

    private suspend fun handleOutput(host: Any?, output: AttachmentsOutput) {
        when (output) {
            is AttachmentsOutput.NativeRequested -> if (owns(output.requestId)) performNative(host, output)

            is AttachmentsOutput.Imported -> if (output.requestId == pick?.requestId) {
                navigator.finishWithResult(AttachmentsPicked, AttachmentSelection(output.requestId, output.attachments))
            }

            is AttachmentsOutput.Cancelled -> if (shouldClose(output.requestId)) navigator.close()

            is AttachmentsOutput.Failed -> if (owns(output.requestId)) store.intent(AttachmentScreenIntent.Failed)

            is AttachmentsOutput.Completed -> if (shouldClose(output.requestId)) navigator.close()
        }
    }

    private suspend fun startNativeRequest() {
        machine.state.first { it !is AttachmentsState.Idle && it !is AttachmentsState.Preparing }
        pick?.let { route ->
            if (machine.send(AttachmentsIntent.Public.Choose(route.requestId, route.support)) != SendResult.Accepted) {
                store.intent(AttachmentScreenIntent.Failed)
            }
        }
        preview?.let { route ->
            val request = initialPreviewRequest(route) ?: return
            if (machine.send(request) != SendResult.Accepted) store.intent(AttachmentScreenIntent.Failed)
        }
    }

    private suspend fun initialPreviewRequest(route: AttachmentPreviewRoute): AttachmentsIntent.Public? {
        if (route.isExportOnOpen) return AttachmentsIntent.Public.Export("$operationPrefix:export", route.id)
        val resource = try {
            storage.read(route.id)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(safeError(error)) { "Initial saved attachment is unavailable" }
            store.intent(AttachmentScreenIntent.Failed)
            return null
        }
        return if (resource.mediaType == "application/pdf") {
            AttachmentsIntent.Public.Open("$operationPrefix:open", route.id)
        } else {
            null
        }
    }

    private suspend fun cancelOwnedRequest() {
        val active = (machine.state.value as? AttachmentsState.Performing)?.requestId ?: return
        if (owns(active)) withContext(NonCancellable) { machine.send(AttachmentsIntent.Public.Cancel(active)) }
    }

    private fun owns(request: String): Boolean = request == pick?.requestId || request.startsWith("$operationPrefix:")

    private fun shouldClose(request: String): Boolean = request == pick?.requestId ||
        (preview?.isExportOnOpen == true && request == "$operationPrefix:export")

    private suspend fun performNative(host: Any?, output: AttachmentsOutput.NativeRequested) {
        try {
            when (val operation = output.operation) {
                is AttachmentNativeOperation.Choose -> {
                    val inputs = native.choose(host, operation.support)
                    if (inputs.isNullOrEmpty()) {
                        machine.send(AttachmentsIntent.Public.Cancel(output.requestId))
                    } else {
                        machine.send(AttachmentsIntent.Internal.Selected(output.requestId, inputs, operation.support))
                    }
                }

                is AttachmentNativeOperation.Open -> {
                    native.open(host, storage.read(operation.id))
                    machine.send(AttachmentsIntent.Internal.NativeCompleted(output.requestId))
                }

                is AttachmentNativeOperation.Export -> {
                    if (native.export(host, storage.read(operation.id))) {
                        machine.send(AttachmentsIntent.Internal.NativeCompleted(output.requestId))
                    } else {
                        machine.send(AttachmentsIntent.Public.Cancel(output.requestId))
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(safeError(error)) { "Native attachment operation failed" }
            machine.send(AttachmentsIntent.Internal.Failed(output.requestId, AttachmentFailure.Unavailable))
        }
    }

    fun close() = navigator.close()

    @AssistedFactory
    fun interface Factory {
        fun create(
            context: ComponentContext,
            navigator: Navigator,
            pick: AttachmentsPickRoute?,
            preview: AttachmentPreviewRoute?,
        ): AttachmentComponent
    }
}

private class AttachmentOperationIdentity(val value: String) : InstanceKeeper.Instance

private class AttachmentStoreHolder(
    val store: Store<AttachmentScreenState, AttachmentScreenIntent, AttachmentScreenAction>,
) : InstanceKeeper.Instance

private fun safeError(error: Exception): Exception = IllegalStateException(error::class.simpleName.orEmpty())
