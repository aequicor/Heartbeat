package io.aequicor.heartbeat.feature.aistudio.impl.di

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.OwnedScope
import io.aequicor.heartbeat.core.di.SavedBundle
import io.aequicor.heartbeat.core.di.ScopeFactory
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.ScopeSavedState
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioState
import io.aequicor.heartbeat.feature.aistudio.api.StudioRemoteOpen
import io.aequicor.heartbeat.feature.aistudio.api.StudioRemoteRefusal
import io.aequicor.heartbeat.feature.aistudio.api.StudioRemoteReply
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.component.AiStudioComponent
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioModel
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioModelTest
import io.aequicor.heartbeat.feature.aistudio.screenmodel.remote.StudioViewSchema
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class RemoteStudioViewsTest {
    private class Harness(val test: TestScope) {
        val enabled = MutableStateFlow(true)
        val scopes = mutableListOf<FakeScope>()
        val hosts = mutableListOf<StudioScreenHost>()
        val views = RemoteStudioViews(
            toggles = object : FeatureToggles {
                @Suppress("UNCHECKED_CAST") // tests only ask for the boolean engine runtime toggle
                override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> = enabled as Flow<T>

                override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = observe(toggle).first()
            },
            scopes = object : ScopeFactory {
                override fun child(parent: ScopeHandle, name: String, restored: SavedBundle?): OwnedScope =
                    FakeScope(test.backgroundScope, name).also { scopes += it }
            },
            profile = FakeScope(test.backgroundScope, "test/profile"),
            graphs = AiStudioGraph.Factory { _, host ->
                hosts += host
                object : AiStudioGraph {
                    override val factory = AiStudioComponent.Factory { _, _ -> error("remote views have no window") }
                    override val model: AiStudioModel = AiStudioModelTest.Fixture(test, AiStudioState.Loading).model
                }
            },
            dispatchers = StandardTestDispatcher(test.testScheduler).let { dispatcher ->
                object : DispatcherProvider {
                    override val main = dispatcher
                    override val default = dispatcher
                    override val io = dispatcher
                }
            },
        )

        suspend fun open(id: String, schema: String = StudioViewSchema.fingerprint) = views.open(id, schema)

        val generations = mutableMapOf<String, String>()

        suspend fun send() = views.send("view", generations["view"].orEmpty(), emptyList())

        /** Opens [id] and collects its frames until the returned job is cancelled. */
        suspend fun attach(id: String) = test.backgroundScope.launch {
            assertIs<StudioRemoteOpen.Opened>(open(id)).frames.collect { frame ->
                frame["generation"]?.jsonPrimitive?.content?.let { generations[id] = it }
            }
        }.also { test.runCurrent() }
    }

    @Test
    fun `views are refused while off, for another schema and beyond the profile limit`() = runTest {
        val harness = Harness(this)

        repeat(4) { harness.attach("view-$it") }
        assertEquals(StudioRemoteRefusal.Busy, assertIs<StudioRemoteOpen.Refused>(harness.open("view-5")).reason)
        assertEquals(
            StudioRemoteRefusal.Incompatible,
            assertIs<StudioRemoteOpen.Refused>(harness.open("view-0", schema = "0000000000000000")).reason,
        )
        harness.enabled.value = false
        runCurrent()
        assertEquals(StudioRemoteRefusal.Unavailable, assertIs<StudioRemoteOpen.Refused>(harness.open("x")).reason)
        assertEquals(List(4) { StudioScreenHost.Remote }, harness.hosts)
        assertTrue(harness.scopes.all { it.isClosed }, "turning remote views off closes them")
    }

    @Test
    fun `a detached view waits for its host, then closes`() = runTest {
        val harness = Harness(this)
        val first = harness.attach("view")
        first.cancel()
        runCurrent()

        advanceTimeBy(29.seconds)
        assertIs<StudioRemoteReply.Applied>(harness.send())
        harness.attach("view")
        advanceTimeBy(60.seconds)
        assertIs<StudioRemoteReply.Applied>(harness.send())
        assertEquals(1, harness.hosts.size, "joining again within the grace period keeps the view")

        harness.scopes.single().let { scope -> assertTrue(!scope.isClosed) }
    }

    @Test
    fun `an unknown view asks the host to open it again`() = runTest {
        val harness = Harness(this)
        harness.attach("view").cancel()
        runCurrent()

        advanceTimeBy(31.seconds)

        assertEquals(StudioRemoteReply.UnknownView, harness.send())
        assertTrue(harness.scopes.single().isClosed)
    }

    @Test
    fun `a view opened but never collected closes after the grace period`() = runTest {
        val harness = Harness(this)
        assertIs<StudioRemoteOpen.Opened>(harness.open("view"))

        advanceTimeBy(31.seconds)

        assertEquals(StudioRemoteReply.UnknownView, harness.send())
    }

    private class FakeScope(override val coroutineScope: CoroutineScope, override val name: String) : OwnedScope {
        override var isClosed = false
        override val savedState = object : ScopeSavedState {
            override fun <T : Any> consume(key: String, serializer: KSerializer<T>): T? = null
            override fun <T : Any> register(key: String, serializer: KSerializer<T>, supplier: () -> T?) = Unit
            override fun unregister(key: String) = Unit
            override fun snapshot() = SavedBundle(emptyMap())
        }

        override fun onClose(action: () -> Unit) = DisposableHandle { }

        override fun close() {
            isClosed = true
        }
    }
}
