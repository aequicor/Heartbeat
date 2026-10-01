package io.aequicor.heartbeat.feature.researchchat.impl.presentation.store

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.SavedBundle
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.ScopeSavedState
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.navigation.GlobalRoutes
import io.aequicor.heartbeat.core.navigation.NavHostFactory
import io.aequicor.heartbeat.core.navigation.NavOptions
import io.aequicor.heartbeat.core.navigation.Navigator
import io.aequicor.heartbeat.core.navigation.PanelsHost
import io.aequicor.heartbeat.core.navigation.ResultContract
import io.aequicor.heartbeat.core.navigation.Route
import io.aequicor.heartbeat.core.navigation.RouteEntry
import io.aequicor.heartbeat.core.navigation.StackHost
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.attachments.api.AttachmentSelection
import io.aequicor.heartbeat.feature.researchchat.api.ResearchChatIntent
import io.aequicor.heartbeat.feature.researchchat.api.ResearchChatOutput
import io.aequicor.heartbeat.feature.researchchat.api.ResearchChatRoute
import io.aequicor.heartbeat.feature.researchchat.api.ResearchChatState
import io.aequicor.heartbeat.feature.researchchat.api.ResearchQuestion
import io.aequicor.heartbeat.feature.researchchat.api.ResearchResourceKind
import io.aequicor.heartbeat.feature.researchchat.api.ResearchResourceScope
import io.aequicor.heartbeat.feature.researchchat.api.ResearchSession
import io.aequicor.heartbeat.feature.researchchat.api.ResearchWorkspace
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.component.ResearchComponent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import pro.respawn.flowmvi.api.Provider
import pro.respawn.flowmvi.dsl.collect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResearchModelTest {
    @Test
    fun `recreating the component replaces its result collector while the retained scope stays alive`() = runTest {
        val fixture = Fixture(this)
        val results = ResearchResultNavigator()
        val featureScope = ResearchTestScope(backgroundScope)
        fun create(lifecycle: LifecycleRegistry) = ResearchComponent(
            DefaultComponentContext(lifecycle),
            results,
            fixture.model,
            ResearchTestHosts(results),
            featureScope,
        )
        val first = LifecycleRegistry().apply { resume() }
        create(first)
        runCurrent()
        assertEquals(1, results.selections.subscriptionCount.value)
        first.destroy()
        runCurrent()
        assertEquals(0, results.selections.subscriptionCount.value)
        assertFalse(featureScope.isClosed)

        val recreated = LifecycleRegistry().apply { resume() }
        create(recreated)
        runCurrent()
        assertEquals(1, results.selections.subscriptionCount.value)
        recreated.destroy()
        runCurrent()
        assertEquals(0, results.selections.subscriptionCount.value)
    }

    @Test
    fun `accepted submit keeps draft until durable acceptance acknowledgement`() = runTest {
        val fixture = Fixture(this)
        val screen = fixture.subscribe()
        fixture.model.store.intent(ResearchScreenIntent.DraftChanged("Compare the sources"))
        fixture.model.store.intent(ResearchScreenIntent.Submit)
        runCurrent()
        assertEquals(ResearchChatIntent.Public.Submit("Compare the sources"), fixture.machine.sent.last())
        assertEquals("Compare the sources", screen.states.value.draft)

        fixture.machine.outputs.emit(ResearchChatOutput.Submitted("first"))
        runCurrent()
        assertEquals("", screen.states.value.draft)
        assertFalse("first" in screen.states.value.drafts)
    }

    @Test
    fun `rejected and failed submits preserve the prompt for retry`() = runTest {
        val fixture = Fixture(this)
        val screen = fixture.subscribe()
        fixture.model.store.intent(ResearchScreenIntent.DraftChanged("Keep this question"))
        fixture.machine.result = SendResult.Ignored
        fixture.model.store.intent(ResearchScreenIntent.Submit)
        runCurrent()
        assertEquals("Keep this question", screen.states.value.draft)

        fixture.machine.result = SendResult.Accepted
        fixture.model.store.intent(ResearchScreenIntent.Submit)
        runCurrent()
        fixture.machine.state.value = researchReady().copy(hasError = true)
        runCurrent()
        assertTrue(screen.states.value.hasError)
        assertEquals("Keep this question", screen.states.value.draft)
        assertEquals("Keep this question", screen.states.value.drafts["first"])
    }

    @Test
    fun `switching questions retains separate input drafts`() = runTest {
        val fixture = Fixture(this)
        val screen = fixture.subscribe()
        fixture.model.store.intent(ResearchScreenIntent.DraftChanged("First question draft"))
        fixture.model.store.intent(ResearchScreenIntent.SelectQuestion("second"))
        runCurrent()
        assertEquals(ResearchChatIntent.Public.SelectQuestion("second"), fixture.machine.sent.last())
        fixture.machine.state.value = researchReady().copy(questionId = "second")
        runCurrent()
        assertEquals("", screen.states.value.draft)
        fixture.model.store.intent(ResearchScreenIntent.DraftChanged("Second question draft"))
        runCurrent()

        fixture.machine.state.value = researchReady()
        runCurrent()
        assertEquals("First question draft", screen.states.value.draft)
        fixture.machine.state.value = researchReady().copy(questionId = "second")
        runCurrent()
        assertEquals("Second question draft", screen.states.value.draft)
    }

    @Test
    fun `late acceptance clears only its question after selection changes`() = runTest {
        val fixture = Fixture(this)
        val screen = fixture.subscribe()
        fixture.model.store.intent(ResearchScreenIntent.DraftChanged("Submitted first prompt"))
        fixture.model.store.intent(ResearchScreenIntent.Submit)
        runCurrent()
        fixture.machine.state.value = researchReady().copy(questionId = "second")
        runCurrent()
        fixture.model.store.intent(ResearchScreenIntent.DraftChanged("Unsubmitted second prompt"))
        runCurrent()

        fixture.machine.outputs.emit(ResearchChatOutput.Submitted("first"))
        runCurrent()
        assertEquals("Unsubmitted second prompt", screen.states.value.draft)
        assertFalse("first" in screen.states.value.drafts)
        assertEquals("Unsubmitted second prompt", screen.states.value.drafts["second"])
    }

    @Test
    fun `typing before acceptance keeps the newer draft in the same question`() = runTest {
        val fixture = Fixture(this)
        val screen = fixture.subscribe()
        fixture.model.store.intent(ResearchScreenIntent.DraftChanged("Submitted prompt"))
        fixture.model.store.intent(ResearchScreenIntent.Submit)
        runCurrent()
        fixture.model.store.intent(ResearchScreenIntent.DraftChanged("Next question draft"))
        runCurrent()

        fixture.machine.outputs.emit(ResearchChatOutput.Submitted("first"))
        runCurrent()
        assertEquals("Next question draft", screen.states.value.draft)
        assertEquals("Next question draft", screen.states.value.drafts["first"])
        assertTrue(screen.states.value.submittedDrafts.isEmpty())
    }

    @Test
    fun `resource form survives import failure and closes only on resource added acknowledgement`() = runTest {
        val fixture = Fixture(this)
        val screen = fixture.subscribe()
        fixture.model.store.intent(ResearchScreenIntent.ShowResourceDialog(true))
        fixture.model.store.intent(ResearchScreenIntent.ResourceKindChanged(ResourceKindUi.Document))
        fixture.model.store.intent(ResearchScreenIntent.ResourceTitleChanged("Research notes"))
        fixture.model.store.intent(ResearchScreenIntent.ResourceValueChanged("Relevant document text"))
        fixture.model.store.intent(ResearchScreenIntent.AddResource)
        runCurrent()
        assertEquals(
            ResearchChatIntent.Public.AddResource(
                ResearchResourceKind.Document,
                "Research notes",
                "Relevant document text",
                ResearchResourceScope.Question,
            ),
            fixture.machine.sent.last(),
        )
        assertTrue(screen.states.value.isResourceDialogOpen)

        fixture.machine.state.value = researchReady().copy(hasError = true)
        runCurrent()
        assertTrue(screen.states.value.isResourceDialogOpen)
        assertEquals("Research notes", screen.states.value.resourceTitle)
        assertEquals("Relevant document text", screen.states.value.resourceValue)

        fixture.machine.outputs.emit(ResearchChatOutput.ResourceAdded)
        runCurrent()
        assertFalse(screen.states.value.isResourceDialogOpen)
        assertEquals("", screen.states.value.resourceTitle)
        assertEquals("", screen.states.value.resourceValue)
        assertEquals(null, screen.states.value.resourceMediaType)
    }

    private class Fixture(private val scope: TestScope) {
        val machine = ResearchTestMachine(researchReady())
        val model = ResearchModel(
            machine = machine,
            route = ResearchChatRoute(researchReady().target),
            scope = ResearchTestScope(scope.backgroundScope),
            factory = HeartbeatStoreFactory(ResearchTestDispatchers(StandardTestDispatcher(scope.testScheduler))),
        )

        suspend fun subscribe(): Provider<ResearchScreenState, ResearchScreenIntent, ResearchScreenAction> {
            val provider =
                CompletableDeferred<Provider<ResearchScreenState, ResearchScreenIntent, ResearchScreenAction>>()
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
}

private class ResearchResultNavigator : Navigator {
    val selections = MutableSharedFlow<AttachmentSelection>()
    override fun navigate(route: Route, options: NavOptions) = error("Not used")
    override fun <R : Any> navigateForResult(route: Route, contract: ResultContract<R>, options: NavOptions) =
        error("Not used")

    @Suppress("UNCHECKED_CAST") // The component requests only AttachmentsPicked in this fixture.
    override fun <R : Any> results(contract: ResultContract<R>): Flow<R> = selections as Flow<R>
    override fun <R : Any> finishWithResult(contract: ResultContract<R>, result: R) = error("Not used")
    override fun close() = error("Not used")
}

private class ResearchTestHosts(private val results: Navigator) : NavHostFactory {
    override fun stack(
        context: ComponentContext,
        parent: Navigator,
        name: String,
        initial: List<Route>,
        local: List<RouteEntry<*>>,
        global: GlobalRoutes,
    ): StackHost = object : StackHost {
        override val navigator = results
        override val backHandler = context.backHandler
        override val stack get() = error("Not used")
        override fun onBack() = error("Not used")
    }

    override fun panels(
        context: ComponentContext,
        parent: Navigator,
        name: String,
        main: Route,
        details: Route?,
        local: List<RouteEntry<*>>,
    ): PanelsHost = error("Not used")
}

private fun researchReady(): ResearchChatState.Ready {
    val target = EngineTarget(EngineId("koog"), EngineBindingId("binding"), ModelId("model"))
    val session = ResearchSession(
        "session",
        "Study",
        target,
        listOf(ResearchQuestion("first"), ResearchQuestion("second")),
    )
    return ResearchChatState.Ready(target, ResearchWorkspace(listOf(session)), "session", "first")
}

private class ResearchTestMachine(initial: ResearchChatState) :
    Machine<ResearchChatState, ResearchChatIntent, ResearchChatOutput> {
    override val name = "research_test"
    override val state = MutableStateFlow(initial)
    override val outputs = MutableSharedFlow<ResearchChatOutput>(extraBufferCapacity = 8)
    val sent = mutableListOf<ResearchChatIntent>()
    var result = SendResult.Accepted

    override suspend fun send(intent: ResearchChatIntent): SendResult {
        sent += intent
        return if (intent is ResearchChatIntent.Public.Start) SendResult.Accepted else result
    }
}

private class ResearchTestDispatchers(dispatcher: CoroutineDispatcher) : DispatcherProvider {
    override val main = dispatcher
    override val default = dispatcher
    override val io = dispatcher
}

private class ResearchTestScope(override val coroutineScope: CoroutineScope) : ScopeHandle {
    override val name = "test/research"
    override val isClosed = false
    override val savedState = object : ScopeSavedState {
        override fun <T : Any> consume(key: String, serializer: KSerializer<T>): T? = null
        override fun <T : Any> register(key: String, serializer: KSerializer<T>, supplier: () -> T?) = Unit
        override fun unregister(key: String) = Unit
        override fun snapshot(): SavedBundle = SavedBundle(emptyMap())
    }

    override fun onClose(action: () -> Unit): DisposableHandle = DisposableHandle { }
}
