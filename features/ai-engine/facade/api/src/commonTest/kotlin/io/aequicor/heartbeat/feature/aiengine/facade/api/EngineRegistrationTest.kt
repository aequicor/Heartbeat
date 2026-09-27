package io.aequicor.heartbeat.feature.aiengine.facade.api

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthContextKey
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthLocationId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthOwnerId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthScope
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSecretId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceInfo
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.ProviderId
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineFactory
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRegistration
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntime
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineSessionSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.validateEngineRegistrations
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EngineRegistrationTest {
    private val descriptor = EngineDescriptor(
        TestTarget.engine,
        "Test",
        EngineFamily.Vendor,
        setOf(EnginePlatform.DesktopWindows),
        AiEngines,
    )
    private val owner = AuthOwnerId("codex")
    private val context = EngineContext(TestTarget.engine, TestTarget.binding)
    private val info = AuthSourceInfo(AuthSourceId("auth"), "Private", AuthRevision.Unknown)
    private val scope = AuthScope(ProviderId("openai"), EndpointOrigin("https://api.example.com"))

    @Test
    fun `registration and foreign ownership rejection do not initialize a runtime factory`() {
        var initialized = false
        val factory = lazy {
            initialized = true
            AcceptingFactory()
        }
        val registration = EngineRegistration(descriptor, owner, factory)
        assertFalse(initialized)
        val foreign = AuthSource.CliLogin(info, scope, AuthOwnerId("other"), AuthLocationId("cli-profile"))
        assertFalse(registration.accepts(foreign, context))
        assertFalse(initialized)
        assertFalse(registration.accepts(foreign, context.copy(engine = EngineId("other"))))
        assertFalse(initialized)
        assertTrue(registration.accepts(AuthSource.ManagedKey(info, scope, AuthSecretId("vault")), context))
        assertTrue(initialized)
    }

    @Test
    fun `factory can narrow shared key compatibility but cannot widen CLI ownership`() {
        val registration = EngineRegistration(descriptor, owner, lazy { AcceptingFactory() })
        val key = AuthSource.ManagedKey(
            info,
            scope.copy(origin = EndpointOrigin("https://foreign.example.com")),
            AuthSecretId("vault"),
        )
        assertFalse(registration.accepts(key, context))
        assertTrue(
            registration.accepts(AuthSource.CliLogin(info, scope, owner, AuthLocationId("cli-profile")), context),
        )
    }

    @Test
    fun `registration rejects duplicate and foreign native stores`() {
        val source = Source(SessionSource(SessionSourceId("native"), descriptor.id, "Native"))
        assertFailsWith<IllegalArgumentException> {
            EngineRegistration(
                descriptor,
                owner,
                lazy { AcceptingFactory() },
                listOf(source, source),
            )
        }
        val foreign = Source(source.source.copy(engine = EngineId("other")))
        assertFailsWith<IllegalArgumentException> {
            EngineRegistration(
                descriptor,
                owner,
                lazy { AcceptingFactory() },
                listOf(foreign),
            )
        }
        assertEquals(SessionHistory::class, SessionHistory.type)
        assertEquals(ResumesSessions::class, ResumesSessions.type)
    }

    @Test
    fun `registry rejects shared owner namespaces before constructing factories`() {
        val factory = lazy<EngineFactory> { error("Must remain lazy") }
        val first = EngineRegistration(descriptor, owner, factory)
        val second = EngineRegistration(descriptor.copy(id = EngineId("other")), owner, factory)
        assertFailsWith<IllegalArgumentException> { validateEngineRegistrations(listOf(first, second)) }
        assertFailsWith<IllegalArgumentException> { validateEngineRegistrations(listOf(first, first)) }
        validateEngineRegistrations(listOf(first))
        assertFalse(factory.isInitialized())
    }

    private class AcceptingFactory : EngineFactory {
        override suspend fun checkRequirements(): EngineAvailability = EngineAvailability.Available
        override fun accepts(source: AuthSource, context: EngineContext): Boolean =
            source.scope.origin.value == "https://api.example.com"
        override fun authContext(context: EngineContext): AuthContextKey = AuthContextKey("test")
        override suspend fun discoverModels(source: AuthSource, context: EngineContext): List<ModelInfo> = emptyList()
        override suspend fun createRuntime(identity: RuntimeIdentity): EngineRuntime =
            throw EngineException(EngineFailure.Engine(EngineFailureReason.Unavailable))
    }

    private class Source(override val source: SessionSource) : EngineSessionSource {
        override val discovery: ListsSessions? = null
        override suspend fun get(ref: SessionRef): EngineSession =
            throw EngineException(EngineFailure.Session(SessionFailureReason.NotFound))
    }
}
