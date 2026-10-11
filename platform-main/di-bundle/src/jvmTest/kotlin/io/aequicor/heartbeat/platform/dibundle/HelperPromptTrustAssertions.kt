package io.aequicor.heartbeat.platform.dibundle

import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.jsonKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aistudio.api.ApprovalMode
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.aistudio.api.StudioSessionSettings
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.HelperHandoff
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.HelperPrompt
import io.aequicor.heartbeat.feature.scheduler.api.HelperSubmission
import io.aequicor.heartbeat.feature.scheduler.api.spi.HelperCreateRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/** Real profile-graph regression for trust changes across durable helper admission. */
internal suspend fun CoroutineScope.assertHelperAdmissionTrust(services: AiEngineTestAccessors) {
    val runtime = services.studioRuntime
    val parent = services.studioRepository.createSession(null, "Parent")
    seedHelperFullApproval(services, HelperId(parent.id))
    val accepted = CompletableDeferred<Unit>()
    val parentRun = async {
        runtime.run(parent.id, "Parent", runtime.defaults(), emptyList()) { accepted.complete(Unit) }
    }
    awaitHelperBarrier("parent acceptance") { accepted.await() }
    val engine = TestAdapter.runtimes.single()
    val parentNative = engine.natives.single()
    parentNative.finish()
    assertEquals(RunOutcome.Completed, parentRun.await())
    val target = requireNotNull(services.modelSelections.observe().first().defaultTarget)
    val host = services.scheduledSessionHosts.maxBy { it.priority }
    val helper = host.createHelper(
        HelperCreateRequest(ActionId("workflow"), parentNative.ref, null, target, "Helper", TrustLevel.Full),
    )
    seedHelperFullApproval(services, helper)
    val owner = (services as HelperPromptTestAccessors).helperPromptOwner
    val sending = async {
        host.promptHelper(
            helper,
            HelperPrompt(
                RequestId("handoff"),
                "Work",
                handoff = HelperHandoff(
                    ownerFeature = owner.feature,
                ),
            ),
        )
    }
    awaitHelperBarrier("fresh admission") { owner.fresh.await() }
    lowerHelperTrustCap(services, helper)
    owner.release.complete(Unit)
    assertIs<HelperSubmission.Accepted>(sending.await())
    val native = engine.natives.last()
    assertEquals(TrustLevel.Ask, native.sent.single().trust)
    assertEquals(ApprovalMode.Ask, runtime.state.value.configurations.getValue(helper.value).applied.approval)
    val store = (services as TestStorageAccessors).stores.keyValue(KeyValueSpec("ai_studio_chats"))
    val records = requireNotNull(store.get(jsonKey("chats", JsonArray.serializer())))
    val saved = records.single { it.jsonObject["id"] == JsonPrimitive(helper.value) }
    val configuration = Json.decodeFromJsonElement(
        StudioSessionSettings.serializer(),
        saved.jsonObject.getValue("configuration"),
    )
    assertEquals(ApprovalMode.Ask, configuration.approval)
    native.finish()
}

internal suspend fun seedHelperFullApproval(
    services: AiEngineTestAccessors,
    helper: HelperId,
    approval: ApprovalMode = ApprovalMode.AutoApprove,
) {
    val store = (services as TestStorageAccessors).stores.keyValue(KeyValueSpec("ai_studio_chats"))
    val key = jsonKey("chats", JsonArray.serializer())
    val full = StudioSessionSettings(services.studioRuntime.defaults().modelId, approval = approval)
    val configuration = Json.encodeToJsonElement(StudioSessionSettings.serializer(), full)
    val records = requireNotNull(store.get(key)).map { record ->
        if (record.jsonObject["id"] == JsonPrimitive(helper.value)) {
            JsonObject(record.jsonObject + ("configuration" to configuration))
        } else {
            record
        }
    }
    store.set(key, JsonArray(records))
    awaitHelperBarrier("configuration $approval") {
        services.studioRuntime.state.first { it.configurations[helper.value]?.applied == full }
    }
}

private suspend fun awaitHelperBarrier(name: String, block: suspend () -> Unit) {
    withContext(Dispatchers.Default) {
        check(
            withTimeoutOrNull(10.seconds) {
                block()
                true
            } == true,
        ) { "Timed out waiting for $name" }
    }
}

private suspend fun lowerHelperTrustCap(services: AiEngineTestAccessors, helper: HelperId) {
    val store = (services as TestStorageAccessors).stores.keyValue(KeyValueSpec("ai_studio_chats"))
    val key = jsonKey("chats", JsonArray.serializer())
    val records = requireNotNull(store.get(key)).map { record ->
        if (record.jsonObject["id"] != JsonPrimitive(helper.value)) return@map record
        val identity = record.jsonObject.getValue("helper").jsonObject
        val limited = JsonObject(
            identity + ("trustCap" to Json.encodeToJsonElement(TrustLevel.serializer(), TrustLevel.Ask)),
        )
        JsonObject(record.jsonObject + ("helper" to limited))
    }
    store.set(key, JsonArray(records))
}
