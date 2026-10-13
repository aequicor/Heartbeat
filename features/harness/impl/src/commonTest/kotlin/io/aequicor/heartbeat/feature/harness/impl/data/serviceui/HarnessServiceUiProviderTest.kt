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
import io.aequicor.heartbeat.feature.harness.impl.data.services.harnessTestData
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
        assertNull(setup.provider.review(id, "prs", chosen))
        setup.fixture.activate()
        val methods = setup.provider.methods(id).first()
        assertNotNull(methods.generation)
        assertEquals(UiMethods(true, listOf(UiMethodInfo("prs", "Pull requests")), methods.generation), methods)

        val args = JsonObject(mapOf("repo" to JsonPrimitive("a/b")))
        val reviewed = assertNotNull(setup.provider.review(id, "prs", chosen))
        assertEquals("fixture", reviewed.source)
        assertNull(setup.provider.review(id, "prs", UiCallContext(session = dispatchSession)))
        assertNull(setup.provider.review(id, "missing", chosen))
        assertEquals(
            UiMethodResult.Success(JsonPrimitive("a/b")),
            setup.provider.call(id, UiMethodCall("prs", args, chosen, reviewed.binding)),
        )
        assertEquals(
            UiMethodResult.Failure(UiMethodFailure.Unavailable),
            setup.provider.call(id, UiMethodCall("prs", args, chosen, "obsolete-binding")),
        )
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
    fun `atomic script replacement emits a new generation with an unchanged catalog and durable revision`() = runTest {
        val setup = setup()
        val fixture = setup.fixture
        val harness = fixture.request.harness
        val owner = harness.id.value
        val context = UiCallContext(preselected = mapOf(HarnessTools.GROUP to setOf(owner)))
        library(HarnessState.Ready(listOf(HarnessEntry(harness))))
        var value = "first"
        fixture.onEvaluate = { script ->
            val captured = value
            script.ui.method(ItemName("record"), "Record") { ScriptUiResult.Value(JsonPrimitive(captured)) }
        }
        fixture.activate()
        val snapshots = mutableListOf<UiMethods>()
        backgroundScope.launch { setup.provider.methods(owner).collect { snapshots += it } }
        runCurrent()
        val first = snapshots.single()
        assertNotNull(first.generation)
        val revision = setup.provider.dataRevision(owner).first()

        value = "second"
        fixture.desired = fixture.request.copy(generation = fixture.request.generation + 1)
        fixture.activate()
        runCurrent()
        val replacement = snapshots.last()
        assertEquals(2, snapshots.size)
        assertEquals(first.methods, replacement.methods)
        assertEquals(true, replacement.isAvailable)
        assertNotEquals(first.generation, replacement.generation)
        assertEquals(revision, setup.provider.dataRevision(owner).first())
        assertEquals(
            UiMethodResult.Success(JsonPrimitive("second")),
            setup.provider.call(owner, UiMethodCall("record", JsonObject(emptyMap()), context)),
        )
        runCurrent()
        assertEquals(2, snapshots.size, "A read does not invalidate the published generation")
    }

    @Test
    fun `without a script host there are no methods`() = runTest {
        val setup = setup(isHostAvailable = false)
        val harness = setup.fixture.request.harness
        val chosen = UiCallContext(preselected = mapOf(HarnessTools.GROUP to setOf(harness.id.value)))
        library(HarnessState.Ready(listOf(HarnessEntry(harness))))

        assertEquals(UiMethods(isAvailable = false), setup.provider.methods(harness.id.value).first())
        assertNull(setup.provider.review(harness.id.value, "prs", chosen))
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
            harnessTestData(),
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
