package io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.feature.serviceasui.api.UiCallContext
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethodFailure
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethodInfo
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethodResult
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethods
import io.aequicor.heartbeat.feature.serviceasui.api.UiOwner
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiMethodCalls
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiPlatform
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiPlatformResult
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.UiSpecDecoding
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.decodeUiSpec
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.UiFacts
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.UiSourceStatus
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.NativeWidgetUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.UiNodeUi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import pro.respawn.flowmvi.api.Provider
import pro.respawn.flowmvi.dsl.collect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private typealias SurfaceProvider = Provider<SurfaceState, SurfaceIntent, SurfaceAction>

class SurfaceModelTest {
    @Test
    fun `automatic sources load once methods are available, with arguments from state`() = runTest {
        val fixture = Fixture(this)
        val screen = fixture.subscribe()
        runCurrent()
        assertEquals(emptyList(), fixture.calls.made)
        assertEquals(UiSourceStatus.Idle, screen.states.value.values.status["prs"])

        fixture.calls.available(true)
        runCurrent()

        assertEquals(listOf("prs" to JsonObject(mapOf("state" to JsonPrimitive("open")))), fixture.calls.made)
        assertEquals(UiSourceStatus.Ready, screen.states.value.values.status["prs"])
        val title = assertIs<UiNodeUi.Text>(column(screen).children[0])
        assertEquals("2 open PRs", title.text)
        fixture.calls.available(false)
        fixture.calls.available(true)
        runCurrent()
        assertEquals(1, fixture.calls.made.size)
    }

    @Test
    fun `inputs update state and calls store results into it`() = runTest {
        val fixture = Fixture(this, available = true)
        val screen = fixture.subscribe()
        runCurrent()

        screen.intent(SurfaceIntent.Input("filter", JsonPrimitive("all")))
        runCurrent()
        assertEquals("all", assertIs<UiNodeUi.TextField>(column(screen).children[1]).value)

        screen.intent(SurfaceIntent.Press(button(screen, 2).press))
        runCurrent()
        assertEquals("prs" to JsonObject(mapOf("state" to JsonPrimitive("all"))), fixture.calls.made.last())
        assertEquals(JsonPrimitive(2), (screen.states.value.values.state["last"] as JsonObject)["count"])
    }

    @Test
    fun `a running action marks its button and failures become notices`() = runTest {
        val fixture = Fixture(this, available = true)
        val screen = fixture.subscribe()
        runCurrent()
        val gate = CompletableDeferred<UiMethodResult>()
        fixture.calls.next = { gate.await() }

        screen.intent(SurfaceIntent.Press(button(screen, 2).press))
        runCurrent()
        assertTrue(button(screen, 2).isBusy)
        gate.complete(UiMethodResult.UserError("Sign in to gh"))
        runCurrent()

        assertFalse(button(screen, 2).isBusy)
        assertEquals(NoticeUi.Message("Sign in to gh"), screen.states.value.notice)
        screen.intent(SurfaceIntent.DismissNotice)
        fixture.calls.next = { UiMethodResult.Failure(UiMethodFailure.TimedOut) }
        screen.intent(SurfaceIntent.Reload("prs"))
        runCurrent()
        assertEquals(NoticeUi.Failed(FailureUi.TimedOut), screen.states.value.notice)
        assertIs<UiSourceStatus.Failed>(screen.states.value.values.status["prs"])
    }

    @Test
    fun `modals open with arguments and close`() = runTest {
        val fixture = Fixture(this, available = true)
        val screen = fixture.subscribe()
        runCurrent()

        screen.intent(SurfaceIntent.Press(button(screen, 3).press))
        runCurrent()
        val modal = screen.states.value.modal!!
        assertEquals("PR 7", modal.title)
        val close = assertIs<UiNodeUi.Button>(modal.body)
        screen.intent(SurfaceIntent.Press(close.press))
        runCurrent()
        assertNull(screen.states.value.modal)

        screen.intent(SurfaceIntent.Press(button(screen, 3).press))
        runCurrent()
        screen.intent(SurfaceIntent.DismissModal)
        runCurrent()
        assertNull(screen.states.value.modal)
    }

    @Test
    fun `prompts, panels, links and other surfaces go to the host and the screen`() = runTest {
        val fixture = Fixture(this, available = true)
        val screen = fixture.subscribe()
        val actions = mutableListOf<SurfaceAction>()
        backgroundScope.launch { screen.actions.collect { actions += it } }
        runCurrent()

        screen.intent(SurfaceIntent.Press(button(screen, 4).press))
        runCurrent()

        assertEquals(listOf("Rank 2 PRs" to true), fixture.host.prompts)
        assertEquals(listOf("map" to JsonObject(mapOf("pr" to JsonPrimitive(7)))), fixture.host.panels)
        assertEquals(
            listOf(
                SurfaceAction.OpenUrl("https://github.com/pulls", isTrusted = true),
                SurfaceAction.OpenSurface("details", JsonObject(emptyMap())),
            ),
            actions,
        )

        screen.intent(SurfaceIntent.OpenLink("https://example.com/7"))
        screen.intent(SurfaceIntent.OpenLink("javascript:alert(1)"))
        runCurrent()
        assertEquals(SurfaceAction.OpenUrl("https://example.com/7", isTrusted = false), actions.last())
        assertEquals(NoticeUi.InvalidLink, screen.states.value.notice)
    }

    @Test
    fun `a sequence stops at the first failure and files need the platform`() = runTest {
        val fixture = Fixture(this, available = true)
        val screen = fixture.subscribe()
        runCurrent()

        screen.intent(SurfaceIntent.Press(button(screen, 5).press))
        runCurrent()
        assertEquals(NoticeUi.InvalidLink, screen.states.value.notice)
        assertEquals(emptyList(), fixture.host.prompts)

        screen.intent(SurfaceIntent.Pick("filter", NativeWidgetUi.Folder, emptyList()))
        runCurrent()
        assertEquals(NoticeUi.Unsupported, screen.states.value.notice)
    }

    @Test
    fun `files follow the platform and a closed dialog stops a sequence quietly`() = runTest {
        val fixture = Fixture(this, available = true)
        val screen = fixture.subscribe()
        runCurrent()

        fixture.platform.result = UiPlatformResult.Cancelled
        screen.intent(SurfaceIntent.Press(button(screen, 6).press))
        runCurrent()
        assertNull(screen.states.value.notice)
        assertEquals(emptyList(), fixture.host.prompts)
        assertEquals(listOf("save open prs.md [md]"), fixture.platform.calls)

        fixture.platform.result = UiPlatformResult.Done()
        screen.intent(SurfaceIntent.Press(button(screen, 6).press))
        runCurrent()
        assertEquals(listOf("saved" to false), fixture.host.prompts)

        fixture.platform.result = UiPlatformResult.Failed
        screen.intent(SurfaceIntent.Press(button(screen, 7).press))
        runCurrent()
        assertEquals(NoticeUi.FileFailed, screen.states.value.notice)
        assertEquals("reveal /work/prs.md", fixture.platform.calls.last())

        fixture.platform.result = UiPlatformResult.Done("/work/prs")
        screen.intent(SurfaceIntent.Pick("filter", NativeWidgetUi.Folder, emptyList()))
        runCurrent()
        assertEquals(JsonPrimitive("/work/prs"), screen.states.value.values.state["filter"])
        assertEquals("pick true []", fixture.platform.calls.last())
    }

    @Test
    fun `cards without an owner only insert prompts and never call`() = runTest {
        val fixture = Fixture(this, available = true, hasOwner = false)
        val screen = fixture.subscribe()
        runCurrent()

        screen.intent(SurfaceIntent.Press(button(screen, 4).press))
        runCurrent()
        assertEquals(listOf("Rank  PRs" to false), fixture.host.prompts)
        screen.intent(SurfaceIntent.Press(button(screen, 2).press))
        runCurrent()
        assertEquals(emptyList(), fixture.calls.made)
        assertEquals(NoticeUi.Unsupported, screen.states.value.notice)
        screen.intent(SurfaceIntent.Press(button(screen, 7).press))
        runCurrent()
        assertEquals(emptyList(), fixture.platform.calls)
    }

    private fun column(screen: SurfaceProvider) = assertIs<UiNodeUi.Column>(screen.states.value.root)

    private fun button(screen: SurfaceProvider, index: Int) = assertIs<UiNodeUi.Button>(column(screen).children[index])

    private class Fixture(private val scope: TestScope, available: Boolean = false, hasOwner: Boolean = true) {
        val calls = FakeCalls(available)
        val host = FakeHost()
        val platform = FakePlatform()
        private val spec = (decodeUiSpec(SPEC) as UiSpecDecoding.Decoded).spec
        private val model = SurfaceModel(
            SurfaceSession(
                spec,
                UiOwner("harness", "h_1").takeIf { hasOwner },
                UiCallContext(),
                JsonObject(emptyMap()),
                UiFacts(),
                host,
            ),
            calls,
            platform,
            HeartbeatStoreFactory(TestDispatchers(StandardTestDispatcher(scope.testScheduler))),
            scope.backgroundScope,
        )

        suspend fun subscribe(): SurfaceProvider {
            val provider = CompletableDeferred<SurfaceProvider>()
            scope.backgroundScope.launch {
                model.store.collect {
                    provider.complete(this)
                    awaitCancellation()
                }
            }
            scope.runCurrent()
            return provider.await()
        }
    }

    private class FakeCalls(available: Boolean) : UiMethodCalls {
        private val methods = MutableStateFlow(UiMethods(available, listOf(UiMethodInfo("prs", "PRs"))))
        val made = mutableListOf<Pair<String, JsonObject>>()
        var next: suspend () -> UiMethodResult = {
            UiMethodResult.Success(
                buildJsonObject {
                    put("count", 2)
                },
            )
        }

        fun available(isAvailable: Boolean) {
            methods.value = methods.value.copy(isAvailable = isAvailable)
        }

        override fun methods(owner: UiOwner): Flow<UiMethods> = methods

        override suspend fun call(
            owner: UiOwner,
            method: String,
            arguments: JsonObject,
            context: UiCallContext,
        ): UiMethodResult {
            made += method to arguments
            return next()
        }
    }

    private class FakeHost : SurfaceHost {
        val prompts = mutableListOf<Pair<String, Boolean>>()
        val panels = mutableListOf<Pair<String, JsonObject>>()

        override suspend fun prompt(text: String, isSubmitted: Boolean): Boolean {
            prompts += text to isSubmitted
            return true
        }

        override suspend fun openPanel(name: String, args: JsonObject): Boolean {
            panels += name to args
            return true
        }
    }

    private class FakePlatform : UiPlatform {
        var result: UiPlatformResult = UiPlatformResult.Unsupported
        val calls = mutableListOf<String>()

        override suspend fun save(text: String, name: String, extensions: List<String>): UiPlatformResult {
            calls += "save $text $name $extensions"
            return result
        }

        override suspend fun reveal(path: String): UiPlatformResult {
            calls += "reveal $path"
            return result
        }

        override suspend fun pick(isFolder: Boolean, extensions: List<String>): UiPlatformResult {
            calls += "pick $isFolder $extensions"
            return result
        }
    }

    private class TestDispatchers(dispatcher: CoroutineDispatcher) : DispatcherProvider {
        override val main: CoroutineDispatcher = dispatcher
        override val default: CoroutineDispatcher = dispatcher
        override val io: CoroutineDispatcher = dispatcher
    }

    private companion object {
        val SPEC = """
            {"version":1,"state":{"filter":"open","last":null},
             "data":{"prs":{"method":"prs","args":{"state":"{{state.filter}}"}}},
             "modals":{"details":{"title":"PR {{args.pr}}",
               "body":{"type":"button","label":"Close","action":{"type":"close"}}}},
             "body":{"type":"column","children":[
               {"type":"text","text":"{{data.prs.count}} {{state.filter}} PRs"},
               {"type":"textField","bind":"filter"},
               {"type":"button","id":"refresh","label":"Refresh",
                "action":{"type":"call","method":"prs","args":{"state":"{{state.filter}}"},"into":"last"}},
               {"type":"button","label":"Open","action":{"type":"open","modal":"details","args":{"pr":7}}},
               {"type":"button","label":"Share","action":{"type":"sequence","actions":[
                 {"type":"prompt","text":"Rank {{data.prs.count}} PRs","submit":true},
                 {"type":"open","surface":"map","presentation":"panel","args":{"pr":7}},
                 {"type":"openUrl","url":"https://github.com/pulls"},
                 {"type":"open","surface":"details"}]}},
               {"type":"button","label":"Broken","action":{"type":"sequence","actions":[
                 {"type":"openUrl","url":"file:///etc"},
                 {"type":"prompt","text":"never"}]}},
               {"type":"button","label":"Save","action":{"type":"sequence","actions":[
                 {"type":"saveFile","text":"{{state.filter}}","name":"prs.md","extensions":["md"]},
                 {"type":"prompt","text":"saved"}]}},
               {"type":"button","label":"Show","action":{"type":"reveal","path":"/work/prs.md"}}
             ]}}
        """.trimIndent()
    }
}
