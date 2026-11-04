package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.LogSink
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthScope
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreatesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityStamp
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineProtocolIdentity
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntimeCandidateRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogConnection
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogEngineEnabled
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogEngineId
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class KoogIntegrationTest {
    private val messages = ConcurrentLinkedQueue<String>()
    private val persisted = PersistedProfile()
    private val app = createGraphFactory<TestAppGraph.Factory>().create(persisted)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        Log.init(
            isDebug = true,
            sinks = listOf(
                LogSink { _, tag, _, message ->
                    if (tag == "NET" || tag == "KoogSdkProbe") messages += "$tag:$message"
                },
            ),
        )
    }

    @AfterTest
    fun tearDown() = runTest {
        app.closeAndAwaitStorages()
        Log.init(isDebug = false)
        Dispatchers.resetMain()
        File(persisted.storageRoot).deleteRecursively()
    }

    @Test
    fun `SDK checks use the real offline transport through the candidate SPI without granting admission`() = runTest {
        val profile = app.profileSessions.open(ProfileId("koog-sdk-contract")).graph as KoogAccessors
        assertTrue(profile.koogConnections.list().isEmpty())
        val registration = (profile as RegisteredEnginesAccess).registeredEngines.all
            .single { it.descriptor.id == KoogEngineId }
        val factory = registration.factory.value
        val identity = RuntimeIdentity(KoogEngineId, AuthSourceId("synthetic-sdk-check"), AuthRevision.Known("1"))
        val request = EngineRuntimeCandidateRequest(identity, LaunchContext(), KoogProvider.OpenAI.id)
        val deadline = TimeSource.Monotonic.markNow() + 10.seconds
        messages.clear()
        val candidate = assertNotNull(factory.openRuntimeCandidate(request, deadline))
        try {
            assertFalse(deadline.hasPassedNow(), messages.joinToString("\n"))
            assertEquals(request, candidate.request)
            assertEquals(KoogEngineId, candidate.evidence.identity.engine)
            assertNull(candidate.evidence.identity.runtimeFingerprint)
            assertNull(candidate.evidence.isProvenanceConfirmed)
            assertTrue(candidate.evidence.isBaselineConfirmed != true)
            assertNull(factory.runtimeRequirements)
            assertEquals(7, messages.count { "POST *** -> 200" in it }, messages.joinToString("\n"))
            assertTrue(messages.any { "unobserved=0" in it }, messages.joinToString("\n"))
            assertTrue(profile.koogConnections.list().isEmpty())
            // Deliberately fictional: even an explicitly supplied stamp cannot promote this partial candidate.
            val forged = EngineCompatibilityStamp(
                KoogEngineId, "fictional", "fictional", "0.0.0",
                EngineProtocolIdentity.ProbedSchema("fictional", "fictional"),
                0, 0, "fictional", "fictional",
            )
            val denied = assertFailsWith<EngineException> { candidate.promote(forged, deadline) }
            assertEquals(
                EngineFailureReason.CompatibilityUnknown,
                assertIs<EngineFailure.Engine>(denied.failure).reason,
            )
        } finally {
            candidate.close()
            candidate.close()
        }
    }

    @Test
    fun registrationAndProfileStorageWorkWithoutProviderRequests() = runTest {
        val toggle = app as TestToggleAccessors
        assertTrue(KoogEngineEnabled in toggle.toggleControl.registered)
        val id = ProfileId("koog-integration")
        val first = app.profileSessions.open(id).graph as KoogAccessors
        val registrations = (first as RegisteredEnginesAccess).registeredEngines.all
        val registration = registrations.single { it.descriptor.id == KoogEngineId }
        val source = AuthSource.NoAuth(
            AuthSourceInfo(AuthSourceId("local"), "Ollama", AuthRevision.Known("1")),
            AuthScope(KoogProvider.Ollama.id, KoogProvider.Ollama.origin),
        )
        val binding = EngineBinding(EngineBindingId("local"), KoogEngineId, source.info.id)
        val connection = KoogConnection(binding, source)
        first.koogConnections.put(connection)
        val identity = RuntimeIdentity(KoogEngineId, source.info.id, source.info.revision)
        assertFailsWith<EngineException> { registration.factory.value.createRuntime(identity) }
        toggle.toggleControl.setOverride(KoogEngineEnabled, true)
        val runtime = registration.factory.value.createRuntime(identity)
        val creator = (runtime.features.resolve(CreatesSessions) as FeatureAccess.Available).feature
        val session = creator.create(CreateSessionRequest(EngineTarget(KoogEngineId, binding.id, ModelId("test"))))
        app.profileSessions.open(ProfileId("other"))
        val second = app.profileSessions.open(id).graph as KoogAccessors
        assertEquals(listOf(connection), second.koogConnections.list())
        val reopened = (second as RegisteredEnginesAccess).registeredEngines.all
            .single { it.descriptor.id == KoogEngineId }
        val stored = reopened.sessionSources.single().get(session.ref)
        val history = (stored.features.resolve(SessionHistory) as FeatureAccess.Available).feature
        assertEquals(emptyList(), history.page().items)
    }

    @Test
    fun sourceUpdatesRequireNewRevisionAndUpdateEveryBinding() = runTest {
        val profile = app.profileSessions.open(ProfileId("koog-revisions")).graph as KoogAccessors
        val source = AuthSource.NoAuth(
            AuthSourceInfo(AuthSourceId("local"), "Ollama", AuthRevision.Known("1")),
            AuthScope(KoogProvider.Ollama.id, KoogProvider.Ollama.origin),
        )
        val first = EngineBinding(EngineBindingId("one"), KoogEngineId, source.info.id)
        val second = first.copy(id = EngineBindingId("two"))
        profile.koogConnections.put(KoogConnection(first, source))
        profile.koogConnections.put(KoogConnection(second, source))
        val changed = source.copy(info = source.info.copy(label = "Updated"))
        assertFailsWith<IllegalArgumentException> {
            profile.koogConnections.put(KoogConnection(first, changed))
        }
        val revised = changed.copy(info = changed.info.copy(revision = AuthRevision.Known("2")))
        profile.koogConnections.put(KoogConnection(first, revised))
        assertEquals(listOf(revised, revised), profile.koogConnections.list().map { it.source })
    }
}
