package io.aequicor.heartbeat.feature.harness.impl.data.serviceui

import io.aequicor.heartbeat.feature.harness.api.HarnessEntry
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessState
import io.aequicor.heartbeat.feature.harness.api.HarnessTools
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.api.ItemName
import io.aequicor.heartbeat.feature.harness.api.script.ScriptUiResult
import io.aequicor.heartbeat.feature.harness.impl.data.services.SchedulerAccessMachine
import io.aequicor.heartbeat.feature.harness.impl.data.services.SchedulerAccessRegistry
import io.aequicor.heartbeat.feature.harness.impl.data.services.SchedulerAccessToggles
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessDispatchFixture
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessUiDispatch
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.connectedAdmission
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.dispatchSession
import io.aequicor.heartbeat.feature.harness.impl.domain.script.CompiledHarnessCode
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCompilationRequest
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCompilationResult
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessEvaluationContext
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessEvaluationResult
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessScriptHost
import io.aequicor.heartbeat.feature.serviceasui.api.UiCallContext
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethodCall
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethodFailure
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethodInfo
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethodResult
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethods
import io.aequicor.heartbeat.feature.serviceasui.api.UiOwnerInfo
import io.aequicor.heartbeat.feature.serviceasui.api.UiOwners
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class HarnessServiceUiProviderTest {
    private val registry = SchedulerAccessRegistry()
    private val toggles = SchedulerAccessToggles()

    @Test
    fun `owners are complete only for a ready library while the feature is on`() = runTest {
        val setup = setup()
        val harness = setup.fixture.request.harness
        val info = UiOwnerInfo(harness.id.value, harness.name.value, harness.title, isEnabled = true)

        assertEquals(UiOwners(isComplete = false), setup.provider.owners.first())
        library(HarnessState.Ready(listOf(HarnessEntry(harness))))
        assertEquals(UiOwners(isComplete = true, listOf(info)), setup.provider.owners.first())
        library(HarnessState.Ready(listOf(HarnessEntry(harness)), isSuspended = true))
        assertEquals(UiOwners(isComplete = false, listOf(info)), setup.provider.owners.first())
        library(HarnessState.Ready(listOf(HarnessEntry(harness))))
        toggles.enabled.value = false
        assertEquals(UiOwners(isComplete = false, listOf(info.copy(isEnabled = false))), setup.provider.owners.first())
    }

    @Test
    fun `active owners follow chat connections and the choice for a new chat`() = runTest {
        val setup = setup()
        val harness = setup.fixture.request.harness
        val id = harness.id.value
        val attachments = mapOf(dispatchSession to setOf(harness.id))
        library(HarnessState.Ready(listOf(HarnessEntry(harness)), attachments = attachments))

        assertEquals(setOf(id), setup.provider.activeOwners(UiCallContext(session = dispatchSession)).first())
        assertEquals(emptySet(), setup.provider.activeOwners(UiCallContext()).first())
        val chosen = UiCallContext(preselected = mapOf(HarnessTools.GROUP to setOf(id, " ")))
        assertEquals(setOf(id), setup.provider.activeOwners(chosen).first())
        library(HarnessState.Ready(listOf(HarnessEntry(harness.copy(isEnabled = false)))))
        assertEquals(emptySet(), setup.provider.activeOwners(chosen).first())
        library(HarnessState.Failed())
        assertEquals(emptySet(), setup.provider.activeOwners(chosen).first())
    }

    @Test
    fun `methods and calls come from running scripts of active harnesses`() = runTest {
        val setup = setup()
        val harness = setup.fixture.request.harness
        val id = harness.id.value
        val chosen = UiCallContext(preselected = mapOf(HarnessTools.GROUP to setOf(id)))
        library(HarnessState.Ready(listOf(HarnessEntry(harness))))
        setup.fixture.onEvaluate = { script ->
            script.ui.method(ItemName("prs"), "Pull requests") { call ->
                ScriptUiResult.Value(call.arguments["repo"] ?: JsonPrimitive("none"))
            }
        }

        assertEquals(UiMethods(isAvailable = false), setup.provider.methods(id).first())
        setup.fixture.activate()
        assertEquals(UiMethods(true, listOf(UiMethodInfo("prs", "Pull requests"))), setup.provider.methods(id).first())

        val args = JsonObject(mapOf("repo" to JsonPrimitive("a/b")))
        assertEquals(
            UiMethodResult.Success(JsonPrimitive("a/b")),
            setup.provider.call(id, UiMethodCall("prs", args, chosen)),
        )
        assertEquals(
            UiMethodResult.Failure(UiMethodFailure.NotFound),
            setup.provider.call(id, UiMethodCall("other", args, chosen)),
        )
        assertEquals(
            UiMethodResult.Failure(UiMethodFailure.Inactive),
            setup.provider.call(id, UiMethodCall("prs", args, UiCallContext(session = dispatchSession))),
        )
    }

    @Test
    fun `without a script host there are no methods`() = runTest {
        val setup = setup(isHostAvailable = false)
        val harness = setup.fixture.request.harness
        val chosen = UiCallContext(preselected = mapOf(HarnessTools.GROUP to setOf(harness.id.value)))
        library(HarnessState.Ready(listOf(HarnessEntry(harness))))

        assertEquals(UiMethods(isAvailable = false), setup.provider.methods(harness.id.value).first())
        assertEquals(
            UiMethodResult.Failure(UiMethodFailure.Unavailable),
            setup.provider.call(harness.id.value, UiMethodCall("prs", JsonObject(emptyMap()), chosen)),
        )
    }

    private fun library(state: HarnessState) {
        registry.library.value = SchedulerAccessMachine(state)
    }

    private fun TestScope.setup(isHostAvailable: Boolean = true): Setup {
        val fixture = HarnessDispatchFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val dispatch = HarnessUiDispatch(
            fixture.runtime,
            connectedAdmission { registry.library.value?.state?.value ?: HarnessState.Idle() },
        )
        val provider = HarnessServiceUiProvider(
            registry,
            toggles,
            StaticHost(isHostAvailable),
            lazyOf(fixture.runtime),
            lazyOf(dispatch),
        )
        return Setup(fixture, provider)
    }

    private class Setup(val fixture: HarnessDispatchFixture, val provider: HarnessServiceUiProvider)

    private class StaticHost(override val isAvailable: Boolean) : HarnessScriptHost {
        override suspend fun compile(request: HarnessCompilationRequest): HarnessCompilationResult = error("unused")
        override suspend fun evaluate(
            code: CompiledHarnessCode,
            context: HarnessEvaluationContext,
        ): HarnessEvaluationResult = error("unused")
        override suspend fun removeCached(harness: HarnessId, item: ItemId) = Unit
    }
}
