package io.aequicor.heartbeat.feature.serviceasui.impl.presentation.editor

import io.aequicor.heartbeat.core.common.HostPlatform
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiIntent
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiOutput
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiState
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiSurfaceRoute
import io.aequicor.heartbeat.feature.serviceasui.api.UiCallContext
import io.aequicor.heartbeat.feature.serviceasui.api.UiEditRejection
import io.aequicor.heartbeat.feature.serviceasui.api.UiLibrary
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethodInfo
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethodResult
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethods
import io.aequicor.heartbeat.feature.serviceasui.api.UiOwner
import io.aequicor.heartbeat.feature.serviceasui.api.UiPlacement
import io.aequicor.heartbeat.feature.serviceasui.api.UiWriteOp
import io.aequicor.heartbeat.feature.serviceasui.api.UiWriteOrigin
import io.aequicor.heartbeat.feature.serviceasui.impl.data.UnsupportedUiPlatform
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiMethodCalls
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiWebDocuments
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.FakeServiceUiMachine
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.TestScopeHandle
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.WriteNoticeUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.place.PrMap
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.place.surface
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.SurfaceModel
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.subscribe
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.testDispatchers
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.testStores
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class EditorModelTest {
    private val map = surface("map", UiPlacement.Panel, revision = 2)

    @Test
    fun `a valid draft is saved compactly by the user over its base revision`() = runTest {
        val fixture = Fixture(this)
        val editor = subscribe(fixture.model.store)

        editor.intent(EditorIntent.ChangeText(HELLO_INDENTED))
        settle()
        assertTrue(editor.states.value.isDirty && editor.states.value.isValid)
        editor.intent(EditorIntent.Save)
        settle()

        val write = assertIs<ServiceUiIntent.Internal.Edit>(fixture.machine.sent.single()).write
        assertEquals(UiWriteOrigin.User, write.origin)
        val put = assertIs<UiWriteOp.Put>(write.op)
        assertEquals(HELLO, put.content.spec)
        assertEquals(2, put.expectedRevision)
        assertEquals(null, put.author)
        assertEquals(NOW, put.at)
        assertEquals(false, editor.states.value.isSaving)
    }

    @Test
    fun `a rejected save tells why and an invalid draft is not saved`() = runTest {
        val fixture = Fixture(this)
        fixture.machine.answer = { ServiceUiOutput.EditRejected(it.id, UiEditRejection.Conflict) }
        val editor = subscribe(fixture.model.store)
        settle()

        editor.intent(EditorIntent.ChangeText(HELLO))
        editor.intent(EditorIntent.Save)
        runCurrent()
        assertEquals(emptyList(), fixture.machine.sent, "an unchecked draft is not saved")
        editor.intent(EditorIntent.ChangeText("{"))
        settle()
        editor.intent(EditorIntent.Save)
        settle()
        assertEquals(emptyList(), fixture.machine.sent)

        editor.intent(EditorIntent.ChangeText(HELLO))
        settle()
        editor.intent(EditorIntent.Save)
        settle()
        assertEquals(WriteNoticeUi.Changed, editor.states.value.notice)
    }

    @Test
    fun `deleting closes the editor and switching writes at the saved revision`() = runTest {
        val fixture = Fixture(this)
        val actions = mutableListOf<EditorAction>()
        val editor = subscribe(fixture.model.store, actions)

        editor.intent(EditorIntent.SetEnabled(isEnabled = false))
        settle()
        editor.intent(EditorIntent.Delete)
        settle()

        val ops = fixture.machine.sent.map { assertIs<ServiceUiIntent.Internal.Edit>(it).write.op }
        assertEquals(
            listOf(UiWriteOp.SetEnabled(map.id, isEnabled = false, 2, NOW), UiWriteOp.Delete(map.id, 2)),
            ops,
        )
        assertEquals(listOf<EditorAction>(EditorAction.Deleted), actions)
    }

    @Test
    fun `the preview draws the last valid draft and loads data only on request`() = runTest {
        val fixture = Fixture(this)
        val editor = subscribe(fixture.model.store)
        settle()
        val first = assertNotNull(editor.states.value.previewKey)
        assertEquals(emptyList(), fixture.calls.called)

        editor.intent(EditorIntent.ChangeText("{"))
        settle()
        assertEquals(first, editor.states.value.previewKey)

        val preview = subscribe(fixture.model.surfaces.value.getValue(first).store)
        editor.intent(EditorIntent.LoadPreview)
        settle()
        assertEquals(listOf("list_prs"), fixture.calls.called)
        assertEquals(UiCallContext(preselected = mapOf("harness" to setOf(PrMap.id))), fixture.calls.contexts.single())
        assertTrue(preview.states.value.hasStarted)

        editor.intent(EditorIntent.ChangeText(HELLO))
        settle()
        assertTrue(editor.states.value.previewKey != first)
    }

    @Test
    fun `typing goes on while a save waits for its outcome`() = runTest {
        val fixture = Fixture(this)
        fixture.machine.answer = { null }
        val editor = subscribe(fixture.model.store)
        settle()

        editor.intent(EditorIntent.ChangeText(HELLO))
        settle()
        editor.intent(EditorIntent.Save)
        runCurrent()
        assertTrue(editor.states.value.isSaving)
        editor.intent(EditorIntent.ChangeText(HELLO_INDENTED))
        runCurrent()
        assertEquals(HELLO_INDENTED, editor.states.value.text)

        advanceTimeBy(15.seconds)
        runCurrent()
        assertEquals(false, editor.states.value.isSaving)
        assertEquals(WriteNoticeUi.Unconfirmed, editor.states.value.notice)
    }

    private fun TestScope.settle() {
        advanceTimeBy(1.seconds)
        runCurrent()
    }

    private class Fixture(scope: TestScope) {
        val machine = FakeServiceUiMachine(ServiceUiState.Ready(library = UiLibrary(listOf(map()))))
        val calls = RecordingCalls()
        private val stores = testStores(scope)
        val model = EditorModel(
            ServiceUiSurfaceRoute(map().id.value),
            machine,
            calls,
            { session, child ->
                SurfaceModel(
                    session,
                    calls,
                    UnsupportedUiPlatform,
                    stores,
                    child,
                    UiWebDocuments.Off,
                )
            },
            object : Clock {
                override fun now(): Instant = NOW
            },
            testDispatchers(scope),
            object : PlatformInfo {
                override val host: HostPlatform = HostPlatform.MacOs
            },
            stores,
            TestScopeHandle(scope.backgroundScope),
        )

        private fun map() = surface("map", UiPlacement.Panel, revision = 2).let {
            it.copy(content = it.content.copy(spec = LISTED))
        }
    }

    private class RecordingCalls : UiMethodCalls {
        val called = mutableListOf<String>()
        val contexts = mutableListOf<UiCallContext>()

        override fun activeOwners(context: UiCallContext): Flow<Set<UiOwner>> = flowOf(setOf(PrMap))

        override fun methods(owner: UiOwner): Flow<UiMethods> =
            flowOf(UiMethods(isAvailable = true, listOf(UiMethodInfo("list_prs", "PRs"))))

        override suspend fun call(
            owner: UiOwner,
            method: String,
            arguments: JsonObject,
            context: UiCallContext,
        ): UiMethodResult {
            called += method
            contexts += context
            return UiMethodResult.Success(JsonPrimitive("ok"))
        }
    }

    private companion object {
        val NOW: Instant = Instant.fromEpochSeconds(100)
        const val LISTED = """{"version":1,"data":{"prs":{"method":"list_prs"}},"body":{"type":"text","text":"PRs"}}"""
        const val HELLO = """{"version":1,"body":{"type":"text","text":"Hello"}}"""
        const val HELLO_INDENTED = "{\n  \"version\": 1,\n  \"body\": { \"type\": \"text\", \"text\": \"Hello\" }\n}"
    }
}
