package io.aequicor.heartbeat.feature.harness.impl.domain

import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.harness.api.Harness
import io.aequicor.heartbeat.feature.harness.api.HarnessActivationRequest
import io.aequicor.heartbeat.feature.harness.api.HarnessApprovalWrite
import io.aequicor.heartbeat.feature.harness.api.HarnessAttachmentWrite
import io.aequicor.heartbeat.feature.harness.api.HarnessEffect
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessIntent
import io.aequicor.heartbeat.feature.harness.api.HarnessItem
import io.aequicor.heartbeat.feature.harness.api.HarnessMachineSpec
import io.aequicor.heartbeat.feature.harness.api.HarnessName
import io.aequicor.heartbeat.feature.harness.api.HarnessOutput
import io.aequicor.heartbeat.feature.harness.api.HarnessReceipt
import io.aequicor.heartbeat.feature.harness.api.HarnessScope
import io.aequicor.heartbeat.feature.harness.api.HarnessState
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.api.ItemName
import io.aequicor.heartbeat.feature.harness.api.ToolPolicySpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Instant

internal val now = Instant.fromEpochMilliseconds(1_000)
internal val code = HarnessItem.Script(ItemId("script"), ItemName("script"), "", "hooks {}")
internal val harness = Harness(
    HarnessId("harness"), HarnessName("test"), "Test", "", HarnessScope.Attached, true,
    listOf(code), ToolPolicySpec(), null, 0, now, now,
)
internal val receipt = HarnessReceipt(RequestId("request"), harness.id, harness.revision)

/** Executes the actual pure spec with profile-owned effect jobs, independently of the toggle observer. */
internal class HarnessMachineFixture(private val scope: CoroutineScope, private val effects: HarnessEffects) :
    HarnessMachine,
    EffectScope<HarnessIntent> {
    override val name: String = "harness"
    override val state = MutableStateFlow<HarnessState>(HarnessMachineSpec.initial)
    override val outputs = MutableSharedFlow<HarnessOutput>()
    override suspend fun send(intent: HarnessIntent): SendResult {
        val next = HarnessMachineSpec.resolve(state.value, intent) ?: return SendResult.Ignored
        state.value = next.to
        next.effects.forEach { effect -> scope.launch { effects.handle(effect, this@HarnessMachineFixture) } }
        return SendResult.Accepted
    }
}

internal class MemoryLibrary : HarnessLibraryStorage {
    var snapshot = HarnessLibrarySnapshot(listOf(harness))
    var beforeLoad: suspend () -> Unit = {}
    var beforeSave: suspend () -> Unit = {}
    var beforeRemove: suspend () -> Unit = {}
    val saved = mutableListOf<HarnessReceipt>()
    var removed = 0
    override suspend fun load(): HarnessLibrarySnapshot {
        beforeLoad()
        return snapshot
    }
    override suspend fun save(harness: Harness, receipt: HarnessReceipt): HarnessReceipt {
        saved += receipt
        beforeSave()
        snapshot = snapshot.copy(harnesses = listOf(harness))
        return receipt
    }
    override suspend fun remove(harness: Harness, receipt: HarnessReceipt): HarnessReceipt {
        removed++
        beforeRemove()
        return receipt
    }
    override suspend fun saveAttachments(write: HarnessAttachmentWrite): HarnessAttachmentWrite = write
    override suspend fun saveApproval(write: HarnessApprovalWrite): HarnessApprovalWrite = write
}

internal class RecordingRuntime : HarnessRuntimeControl {
    override val isAvailable = true
    val activated = mutableListOf<HarnessActivationRequest>()
    val deactivated = mutableListOf<HarnessEffect.Deactivate>()
    var canRemove = true
    var isRemovalCurrent = true
    override suspend fun activate(request: HarnessActivationRequest): Boolean {
        activated += request
        return true
    }
    override suspend fun deactivate(effect: HarnessEffect.Deactivate): Boolean {
        deactivated += effect
        return true
    }
    override suspend fun remove(effect: HarnessEffect.Remove): HarnessRemovalResult = when {
        !isRemovalCurrent -> HarnessRemovalResult.Obsolete
        canRemove -> HarnessRemovalResult.Ready
        else -> HarnessRemovalResult.Retry
    }
}

internal class RecordingFeedback : EffectScope<HarnessIntent> {
    val sent = mutableListOf<HarnessIntent>()
    override suspend fun send(intent: HarnessIntent): SendResult {
        sent += intent
        return SendResult.Accepted
    }
}
