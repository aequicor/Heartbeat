package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.navigation.RouteEntry
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.core.statemachine.MachineRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.HookedToolCall
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHook
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHookContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionOwner
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolHookVerdict
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioHarnesses
import io.aequicor.heartbeat.feature.harness.api.HarnessDetailRoute
import io.aequicor.heartbeat.feature.harness.api.HarnessDraft
import io.aequicor.heartbeat.feature.harness.api.HarnessEnabled
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessIntent
import io.aequicor.heartbeat.feature.harness.api.HarnessItem
import io.aequicor.heartbeat.feature.harness.api.HarnessItemRoute
import io.aequicor.heartbeat.feature.harness.api.HarnessMachineKey
import io.aequicor.heartbeat.feature.harness.api.HarnessName
import io.aequicor.heartbeat.feature.harness.api.HarnessOutput
import io.aequicor.heartbeat.feature.harness.api.HarnessRoute
import io.aequicor.heartbeat.feature.harness.api.HarnessScope
import io.aequicor.heartbeat.feature.harness.api.HarnessState
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.api.ItemName
import io.aequicor.heartbeat.feature.harness.api.ItemStatus
import io.aequicor.heartbeat.feature.scheduler.api.EventKeys
import io.aequicor.heartbeat.feature.scheduler.api.EventOrigin
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerBus
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class HarnessIntegrationTest {
    private val persisted = PersistedProfile()
    private val app = createGraphFactory<TestAppGraph.Factory>().create(persisted)
    private val toggles = app as TestToggleAccessors

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = runTest {
        app.closeAndAwaitStorages()
        Dispatchers.resetMain()
        File(persisted.storageRoot).deleteRecursively()
    }

    @Test
    fun `settings section routes of the library are registered in the profile registry`() = runTest {
        val profile = app.profileSessions.open(ProfileId("harness-routes"))
        try {
            val graph = profile.graph as TestHarnessHookAccessors
            val routes = graph.harnessProfileRoutes.map { it.routeClass }
            val expected = listOf(
                HarnessRoute::class,
                HarnessDetailRoute::class,
                HarnessItemRoute::class,
            )
            assertTrue(routes.containsAll(expected), "registered: $routes")
            // The studio's "+" menu is backed by the harness library, not by the no-op default.
            assertTrue(graph.studioHarnesses != StudioHarnesses.None)
        } finally {
            app.profileSessions.close()
        }
    }

    @Test
    fun `real profile activation supplies ownership runtime when its registered flag is enabled`() = runTest {
        val flag = assertIs<FeatureToggle.Flag>(
            toggles.toggleControl.registered.single { it.key == "harness.process_ownership" },
        )
        toggles.toggleControl.setOverride(HarnessEnabled, true)
        toggles.toggleControl.setOverride(flag, true)
        val profile = app.profileSessions.open(ProfileId("harness-ownership"))
        try {
            val bus = (profile.graph as TestHarnessHookAccessors).harnessSchedulerBus
            withContext(app.dispatchers.default) {
                withTimeout(30.seconds) {
                    val machine = app.machines.observe(HarnessMachineKey).filterNotNull().first()
                    machine.state.filterIsInstance<HarnessState.Ready>().first()
                    coroutineScope {
                        val published = async(start = CoroutineStart.UNDISPATCHED) {
                            bus.events.first { it.key == EventKeys.custom("harness.test.ownership_host") }
                        }
                        val code = ownershipScript()
                        create(machine, code)
                        awaitInitialActivation(machine, code.id)
                        val event = published.await()
                        assertTrue(Regex("[0-9a-f]{32}").matches(event.payload.orEmpty()))
                        assertEquals("harness", assertIs<EventOrigin.Feature>(event.origin).name)
                    }
                }
            }
        } finally {
            app.profileSessions.close()
        }
    }

    @Test
    fun `baseline-off harness stays lazy then loads and reuses the library through toggle cycles`() = runTest {
        assertTrue(HarnessEnabled in toggles.toggleControl.registered)
        assertTrue(HarnessEnabled.default)
        val profileId = ProfileId("harness")
        val profile = app.profileSessions.open(profileId)
        // Construct facade/hooks first while harness stays disabled: the cycle must remain lazy.
        (profile.graph as AiEngineTestAccessors).engineFacade.engines.state.value
        val hooks = (profile.graph as TestHarnessHookAccessors).harnessSessionHooks
        // Planning may intercept conservatively while its toggle loads; harness observers must stay disabled.
        assertTrue(hooks.none { it.isObserving })
        assertNull(app.machines.find(HarnessMachineKey))
        toggles.toggleControl.setOverride(HarnessEnabled, true)
        withContext(app.dispatchers.default) {
            withTimeout(30.seconds) {
                val machine = app.machines.observe(HarnessMachineKey).filterNotNull().first()
                machine.state.filterIsInstance<HarnessState.Ready>().first()
                val code = hookScript()
                create(machine, code)
                val saved = awaitInitialActivation(machine, code.id)
                assertTrue(saved.isRuntimeAvailable)
                // Only an explicit connection activates a harness, so the hooks run for the connected chat.
                machine.send(HarnessIntent.Public.Attach(RequestId("attach"), HarnessId("harness"), HOOKED_SESSION))
                machine.state.filterIsInstance<HarnessState.Ready>().first {
                    HarnessId("harness") in it.attachments[HOOKED_SESSION].orEmpty()
                }
                val call = assertSchedulerHooks(hooks, (profile.graph as TestHarnessHookAccessors).harnessSchedulerBus)
                val generation = assertIs<ItemStatus.Active>(saved.harnesses.single().itemStatus[code.id]).generation
                toggles.toggleControl.setOverride(HarnessEnabled, false)
                machine.state.first { it.isSuspended }
                assertTrue(hooks.map { it.beforeTool(call) }.all { it == ToolHookVerdict.Continue })
                toggles.toggleControl.setOverride(HarnessEnabled, true)
                val resumed = machine.state.filterIsInstance<HarnessState.Ready>().first {
                    !it.isSuspended && it.harnesses.single().itemStatus[code.id] is ItemStatus.Active
                }
                val resumedGeneration = assertIs<ItemStatus.Active>(
                    resumed.harnesses.single().itemStatus[code.id],
                ).generation
                assertTrue(resumedGeneration > generation)
                assertSame(machine, app.machines.find(HarnessMachineKey))
            }
        }
        app.profileSessions.close()
        val reopened = app.profileSessions.open(profileId)
        withContext(app.dispatchers.default) {
            withTimeout(30.seconds) {
                val machine = app.machines.observe(HarnessMachineKey).filterNotNull().first()
                val restored = machine.state.filterIsInstance<HarnessState.Ready>().first {
                    it.harnesses.singleOrNull()?.itemStatus?.get(ItemId("code")) is ItemStatus.Active
                }
                assertEquals(HarnessName("test"), restored.harnesses.single().harness.name)
                assertTrue(restored.isRuntimeAvailable)
                // Reverse construction order: startup already activated harness before this explicit facade read.
                (reopened.graph as AiEngineTestAccessors).engineFacade.engines.state.value
                assertTrue((reopened.graph as TestHarnessHookAccessors).harnessSessionHooks.any { it.isIntercepting })
            }
        }
        app.profileSessions.close()
    }

    private suspend fun awaitInitialActivation(
        machine: MachineRef<HarnessState, *, HarnessOutput>,
        item: ItemId,
    ): HarnessState.Ready = try {
        machine.state.filterIsInstance<HarnessState.Ready>().first { state ->
            val status = state.harnesses.singleOrNull()?.itemStatus?.get(item)
            assertTrue(
                status !is ItemStatus.Failed && status != ItemStatus.Unsupported,
                "Initial script activation failed: status=$status, runtimeAvailable=${state.isRuntimeAvailable}",
            )
            status is ItemStatus.Active
        }
    } catch (error: TimeoutCancellationException) {
        val state = machine.state.value
        val ready = state as? HarnessState.Ready
        val status = ready?.harnesses?.singleOrNull()?.itemStatus?.get(item)
        throw AssertionError(
            "Timed out at initial script activation: state=${state::class.simpleName}, " +
                "status=$status, runtimeAvailable=${ready?.isRuntimeAvailable}",
            error,
        )
    }

    /** Edits are Internal and only harness:impl holds the full machine; the cast stands in for it here. */
    private suspend fun create(machine: MachineRef<HarnessState, *, HarnessOutput>, code: HarnessItem) {
        @Suppress("UNCHECKED_CAST") // The registry reference dispatches every intent of its spec.
        val library = machine as MachineRef<HarnessState, HarnessIntent, HarnessOutput>
        library.send(
            HarnessIntent.Internal.Create(
                RequestId("create"),
                HarnessId("harness"),
                HarnessDraft(HarnessName("test"), "Test", scope = HarnessScope.Attached, items = listOf(code)),
                null,
                Instant.fromEpochMilliseconds(1_000),
            ),
        )
    }

    private suspend fun assertSchedulerHooks(hooks: Set<SessionHook>, bus: SchedulerBus): HookedToolCall =
        coroutineScope {
            val event = async(start = CoroutineStart.UNDISPATCHED) {
                bus.events.first { it.key == EventKeys.custom("harness.test.hook_timer") }
            }
            val call = assertDirectHooks(hooks)
            val published = event.await()
            assertEquals("harness", assertIs<EventOrigin.Feature>(published.origin).name)
            assertEquals("timer finished", published.payload)
            call
        }

    private suspend fun assertDirectHooks(hooks: Set<SessionHook>): HookedToolCall {
        val context = SessionHookContext(
            HOOKED_SESSION,
            null,
            null,
            null,
            SessionOwner("harness-test"),
        )
        val call = HookedToolCall(context, "read", AgentToolAction.Read, JsonObject(emptyMap()))
        // No Opened notification: direct hooks must prime their trusted route themselves.
        assertTrue(hooks.map { it.beforeTool(call) }.any { it is ToolHookVerdict.Deny })
        assertTrue(hooks.map { it.beforePrompt(context, "original") }.contains("Harness context"))
        return call
    }

    private fun hookScript(): HarnessItem.Script = HarnessItem.Script(
        ItemId("code"),
        ItemName("code"),
        "",
        """
        check(script.item.value == "code")
        script.hooks.beforePrompt { _, _ ->
            script.scheduler.at(kotlin.time.Instant.fromEpochMilliseconds(0)) {
                script.scheduler.publish(
                    io.aequicor.heartbeat.feature.harness.api.ItemName("hook_timer"),
                    "timer finished",
                )
            }
            "Harness context"
        }
        script.hooks.beforeTool {
            io.aequicor.heartbeat.feature.aiengine.facade.api.ToolHookVerdict.Deny("Harness test denial")
        }
        """.trimIndent(),
    )

    private fun ownershipScript(): HarnessItem.Script = HarnessItem.Script(
        ItemId("ownership"),
        ItemName("ownership"),
        "",
        """
        check(script.ownership !== io.aequicor.heartbeat.feature.harness.api.process.UnavailableScriptProcessOwnership)
        script.scheduler.at(kotlin.time.Instant.fromEpochMilliseconds(0)) {
            val host = checkNotNull(script.ownership.host())
            check(script.ownership.host() == host)
            script.scheduler.publish(
                io.aequicor.heartbeat.feature.harness.api.ItemName("ownership_host"),
                host.value,
            )
        }
        """.trimIndent(),
    )
}

@ContributesTo(ProfileScope::class)
interface TestHarnessHookAccessors {
    val harnessSessionHooks: Set<SessionHook>
    val harnessSchedulerBus: SchedulerBus

    @ForScope(ProfileScope::class)
    val harnessProfileRoutes: Set<RouteEntry<*>>
    val studioHarnesses: StudioHarnesses
}

private val HOOKED_SESSION = SessionRef(EngineId("test"), SessionSourceId("test"), "harness-session")
