package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthCheckBasis
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthScope
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceDraft
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthVerdict
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.ProviderId
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.api.DeepSeekEngine
import io.aequicor.heartbeat.feature.aiengine.facade.api.AiEngines
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Explicit local prerequisite, never a model/protocol/session baseline. Credential material stays in DSH;
 * the isolated Heartbeat profile stores only an external CLI reference and receives a redacted local verdict.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeepSeekLiveMetadataTest {
    private lateinit var persisted: PersistedProfile
    private lateinit var app: TestAppGraph
    private var isMainSet = false

    @BeforeTest
    fun start() {
        assumeTrue("Explicit DSH metadata opt-in required", java.lang.Boolean.getBoolean("heartbeat.dsh.liveMetadata"))
        Dispatchers.setMain(UnconfinedTestDispatcher())
        isMainSet = true
        persisted = PersistedProfile()
        app = createGraphFactory<TestAppGraph.Factory>().create(persisted)
    }

    @AfterTest
    fun finish() {
        if (!isMainSet) return
        try {
            runTest { if (::app.isInitialized) app.closeAndAwaitStorages() }
        } finally {
            Dispatchers.resetMain()
            if (::persisted.isInitialized) File(persisted.storageRoot).deleteRecursively()
        }
    }

    @Test
    fun `installed DSH and local login are observed without native sessions or model requests`() = runTest {
        val toggles = (app as TestToggleAccessors).toggleControl
        toggles.setOverride(AiEngines, true)
        toggles.setOverride(DeepSeekEngine.Enabled, true)
        val services = (app.profileSessions.open(ProfileId("dsh-live-metadata")).graph as EngineServicesAccessor)
            .engineServices
        val engine = services.facade.engines.refresh(DeepSeekEngine.Id)
        assertEquals(EngineAvailability.Available, engine.availability)
        val source = services.sources.register(
            AuthSourceDraft.CliLogin(
                "DSH metadata probe",
                AuthScope(ProviderId("deepseek"), EndpointOrigin("https://api.deepseek.com")),
                DeepSeekEngine.AuthOwner,
                DeepSeekEngine.AuthLocation,
            ),
        )
        val binding = services.facade.bindings.connect(DeepSeekEngine.Id, source.info.id)
        // The model id is unused by DSH's local auth context. It is never sent to the CLI or a provider.
        val result = services.facade.bindings.check(
            EngineTarget(DeepSeekEngine.Id, binding.id, ModelId("metadata-only")),
        )
        val report = buildJsonObject {
            put("engine", DeepSeekEngine.Id.value)
            put("availability", "Available")
            put("authVerdict", result.auth.verdict.name)
            put("authBasis", result.auth.basis.name)
            put("remoteAuthorizationConfirmed", false)
            put("nativeSessionBaselineConfirmed", false)
            put("outboundModelRequests", 0)
        }
        val destination = checkNotNull(System.getProperty("heartbeat.dsh.metadataReport"))
        File(destination).apply { parentFile.mkdirs() }.writeText(report.toString())
        assertEquals(AuthCheckBasis.Local, result.auth.basis)
        assertEquals(AuthVerdict.Authenticated, result.auth.verdict)
    }
}
