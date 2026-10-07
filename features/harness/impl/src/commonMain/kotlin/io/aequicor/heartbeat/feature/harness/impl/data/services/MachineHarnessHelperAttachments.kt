package io.aequicor.heartbeat.feature.harness.impl.data.services

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.MachineRef
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.harness.api.HarnessEnabled
import io.aequicor.heartbeat.feature.harness.api.HarnessIntent
import io.aequicor.heartbeat.feature.harness.api.HarnessMachineKey
import io.aequicor.heartbeat.feature.harness.api.HarnessMutation
import io.aequicor.heartbeat.feature.harness.api.HarnessOutput
import io.aequicor.heartbeat.feature.harness.api.HarnessRejection
import io.aequicor.heartbeat.feature.harness.api.HarnessState
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessHelperAttachments
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessHelperBinding
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

/**
 * Pins a public machine handle and subscribes before Attach. Only committed attachments or the exact durable
 * receipt confirm success. A pending attachment write is never proof. Busy waits share one bounded deadline;
 * the caller's live publisher admission cancels this operation when its authority changes.
 */
@ContributesBinding(ProfileScope::class)
@Inject
internal class MachineHarnessHelperAttachments(
    private val machines: MachineRegistry,
    private val toggles: FeatureToggles,
) : HarnessHelperAttachments {
    private val log = Log.tag("HarnessHelperAttachments")

    override suspend fun ensureAttached(binding: HarnessHelperBinding): Boolean {
        checkNotNull(binding.session) { "Helper attachment has no bound session" }
        val machine = machines.find(HarnessMachineKey) ?: return false
        return withTimeoutOrNull(1500.milliseconds) { attach(machine, binding) } == true
    }

    private suspend fun attach(machine: Library, binding: HarnessHelperBinding): Boolean {
        var attempts = 0
        while (attempts < ATTACH_ATTEMPTS) {
            val ready = usable(machine, binding) ?: return false
            if (binding.harness in ready.attachments[binding.session].orEmpty()) return true
            if (ready.attachmentWrite != null || ready.pending.values.any { it is HarnessMutation.Remove }) {
                machine.state.first { it != ready }
                continue
            }
            attempts++
            log.v { "Request durable helper attachment" }
            when (submit(machine, binding)) {
                AttachmentResult.Saved -> {
                    val committed = usable(machine, binding) ?: return false
                    return binding.harness in committed.attachments[binding.session].orEmpty()
                }

                AttachmentResult.Refused -> return false

                AttachmentResult.Busy -> Unit
            }
        }
        return false
    }

    private suspend fun usable(machine: Library, binding: HarnessHelperBinding): HarnessState.Ready? {
        if (!toggles.get(HarnessEnabled) || machines.find(HarnessMachineKey) !== machine) return null
        val ready = machine.state.value as? HarnessState.Ready ?: return null
        return ready.takeIf {
            !it.isSuspended && it.isRuntimeAvailable && it.pending[binding.harness] !is HarnessMutation.Remove &&
                it.harnesses.any { entry -> entry.harness.id == binding.harness && entry.harness.isEnabled }
        }
    }

    private suspend fun submit(machine: Library, binding: HarnessHelperBinding): AttachmentResult = coroutineScope {
        val receipt = CompletableDeferred<AttachmentResult>()
        val outputs = launch(start = CoroutineStart.UNDISPATCHED) {
            machine.outputs.collect { it.attachmentResult(binding)?.let(receipt::complete) }
        }
        val states = launch(start = CoroutineStart.UNDISPATCHED) {
            machine.state.collect { state ->
                if (state is HarnessState.Ready && binding.harness in state.attachments[binding.session].orEmpty()) {
                    receipt.complete(AttachmentResult.Saved)
                }
            }
        }
        try {
            if (usable(machine, binding) == null) return@coroutineScope AttachmentResult.Refused
            val result = machine.send(
                HarnessIntent.Public.Attach(
                    binding.attachRequest,
                    binding.harness,
                    checkNotNull(binding.session),
                ),
            )
            if (result != SendResult.Accepted) AttachmentResult.Refused else receipt.await()
        } finally {
            outputs.cancel()
            states.cancel()
        }
    }
}

private typealias Library = MachineRef<HarnessState, HarnessIntent.Public, HarnessOutput>
private enum class AttachmentResult { Saved, Busy, Refused }

private fun HarnessOutput.attachmentResult(binding: HarnessHelperBinding): AttachmentResult? = when {
    this is HarnessOutput.Attached && requestId == binding.attachRequest && id == binding.harness &&
        session == binding.session -> AttachmentResult.Saved

    this is HarnessOutput.Rejected && requestId == binding.attachRequest ->
        if (reason == HarnessRejection.Busy) AttachmentResult.Busy else AttachmentResult.Refused

    this is HarnessOutput.StorageFailed && requestId == binding.attachRequest -> AttachmentResult.Refused

    else -> null
}

private const val ATTACH_ATTEMPTS = 3
