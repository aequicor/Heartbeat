package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.core.secrets.Secret
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthScope
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsEnabled
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActionSafetyEnabled
import io.aequicor.heartbeat.feature.aiengine.facade.api.AiEngines
import io.aequicor.heartbeat.feature.aiengine.facade.api.CompatibleProtocol
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SecurityMode
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionQuery
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallStatus
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogCodingTools
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogEngineEnabled
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogEngineId
import io.aequicor.heartbeat.feature.aiengine.pi.api.PiEnabled
import io.aequicor.heartbeat.feature.aiengine.pi.api.PiEngineId
import io.aequicor.heartbeat.feature.aistudio.api.ApprovalMode
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.aistudio.api.StudioEngineRuntime
import io.aequicor.heartbeat.feature.aistudio.api.StudioLocalProjects
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermission
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Real provider, production Metro graph and actual shell effects in a disposable project for both Koog and Pi.
 * Opt in with `:platform-main:di-bundle:securityLiveTest` and HEARTBEAT_SECURITY_LIVE_KEY_FILE, _ENDPOINT, _MODEL.
 * Credentials enter only the isolated profile vault. This spends provider quota and is excluded from `check`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SecurityLiveE2eTest {
    private val persisted by lazy { PersistedProfile() }
    private val graphs = mutableListOf<TestAppGraph>()
    private var project: File? = null
    private var isEnabled = false

    @BeforeTest
    fun setUp() {
        assumeTrue(System.getProperty("heartbeat.security.liveTest") == "true")
        val home = File(checkNotNull(System.getProperty("heartbeat.security.liveHome")))
        check(File(System.getProperty("user.home")) == home) { "Live tests require an isolated JVM home" }
        check(home.mkdirs() || home.isDirectory)
        isEnabled = true
        // Real IO/native startup and cancellation must advance their timers without a test scheduler.
        Dispatchers.setMain(Dispatchers.Default.limitedParallelism(1))
    }

    @AfterTest
    fun tearDown() {
        if (!isEnabled) return
        try {
            runBlocking { graphs.forEach { it.closeAndAwaitStorages() } }
        } finally {
            Dispatchers.resetMain()
            File(persisted.storageRoot).deleteRecursively()
            project?.deleteRecursively()
        }
    }

    @Test
    fun `Koog obeys profile trust confirmation denial and live mode change`() = verify(KoogEngineId)

    @Test
    fun `Pi obeys profile trust confirmation denial and live mode change`() = verify(PiEngineId)

    private fun verify(engine: EngineId): Unit = runBlocking {
        withTimeout(600.seconds) {
            val app = createGraphFactory<TestAppGraph.Factory>().create(persisted).also { graphs += it }
            val toggles = (app as TestToggleAccessors).toggleControl
            for (toggle in listOf(
                AiEngines,
                KoogEngineEnabled,
                KoogCodingTools,
                PiEnabled,
                EngineConnectionsEnabled,
                StudioEngineRuntime,
                StudioLocalProjects,
            )) {
                toggles.setOverride(toggle, true)
            }
            // The scenarios exercise the manual permission UX; the structural review has its own live E2E.
            toggles.setOverride(ActionSafetyEnabled, false)
            val services = app.profileSessions.open(ProfileId("security-live")).graph as AiEngineTestAccessors
            phase("${engine.value}: profile ready")
            val target = connect(services, engine, "Synthetic security E2E")
            phase("${engine.value}: connection ready")
            services.modelSelections.update { it.withDefault(target).withModel(target, true) }
            services.studioRepository.observeModels().first { it.isNotEmpty() }
            val directory = Files.createTempDirectory("hb-security-live-").toFile().also { project = it }
            val workspace = services.localWorkspaces.registerManaged(directory.absolutePath).ref
            val chat = services.studioRepository.createSession(workspace.value, "Synthetic security E2E")
            try {
                scenario(services, chat.id, target, directory, SecurityMode.Trust, Decision.NoPrompt)
                scenario(services, chat.id, target, directory, SecurityMode.Automatic, Decision.Allow)
                scenario(services, chat.id, target, directory, SecurityMode.Automatic, Decision.Deny)
                scenario(services, chat.id, target, directory, SecurityMode.Ask, Decision.SwitchToTrust)
                scenario(services, chat.id, target, directory, SecurityMode.Automatic, Decision.Cancel)
            } finally {
                services.studioRuntime.cancel(chat.id)
            }
        }
    }

    private suspend fun scenario(
        services: AiEngineTestAccessors,
        chat: String,
        target: EngineTarget,
        directory: File,
        mode: SecurityMode,
        decision: Decision,
    ) = coroutineScope {
        services.agentSecuritySettings.save(mode)
        services.agentSecuritySettings.modes.first { it == mode }
        val output = File(directory, "${decision.name}.txt")
        val marker = "HEARTBEAT-LIVE-${UUID.randomUUID()}"
        val command = "printf '%s' '$marker' > '${output.absolutePath}'"
        val observed = mutableSetOf<String>()
        val observer = launch(start = CoroutineStart.UNDISPATCHED) {
            observePermissions(services, chat, command, output, decision, observed)
        }
        try {
            val settings = services.studioRuntime.defaults().copy(
                modelId = Json.encodeToString(EngineTarget.serializer(), target),
                // Old per-chat preferences must not override the profile setting in either direction.
                approval = if (mode == SecurityMode.Trust) ApprovalMode.Ask else ApprovalMode.AutoApprove,
            )
            val prompt = """
                This is a synthetic shell permission test in an isolated temporary project.
                Use only the ${shellName(target)} shell tool exactly once to execute this exact command:
                $command
                Do not use file editing tools or any other command. If execution is denied, stop without retrying
                or using another tool. After execution, reply DONE. Do not ask a conversational question.
            """.trimIndent()
            phase("${target.engine.value}: $decision started")
            val outcome = withTimeout(150.seconds) {
                services.studioRuntime.run(chat, prompt, settings, emptyList()) {}
            }
            assertEquals(if (decision == Decision.Cancel) RunOutcome.Stopped else RunOutcome.Completed, outcome)
            assertEquals(if (decision == Decision.NoPrompt) 0 else 1, observed.size, "One exact tool decision")
            assertEffect(output, marker, decision)
            services.studioRuntime.state.first { state -> state.permissions.none { it.sessionId == chat } }
            assertShellCall(services, target, command, decision)
            phase("${target.engine.value}: $decision verified")
        } finally {
            observer.cancelAndJoin()
        }
    }

    private suspend fun observePermissions(
        services: AiEngineTestAccessors,
        chat: String,
        command: String,
        output: File,
        decision: Decision,
        observed: MutableSet<String>,
    ) {
        services.studioRuntime.state.collect { state ->
            for (permission in state.permissions.filter { it.sessionId == chat }) {
                if (!observed.add(permission.requestId)) continue
                assertFalse(output.exists(), "No shell effect before an explicit decision")
                assertTrue(
                    command in permission.title + permission.description.orEmpty(),
                    "The confirmation must identify the exact shell command",
                )
                answer(services, permission, decision)
            }
        }
    }

    private suspend fun answer(services: AiEngineTestAccessors, permission: StudioPermission, decision: Decision) {
        when (decision) {
            Decision.NoPrompt -> error("Trust unexpectedly requested confirmation")

            Decision.SwitchToTrust -> services.agentSecuritySettings.save(SecurityMode.Trust)

            Decision.Cancel -> services.studioRuntime.cancel(permission.sessionId)

            Decision.Allow, Decision.Deny -> {
                val suffix = if (decision == Decision.Allow) "allow" else "deny"
                val option = permission.options.single { it.id == suffix || it.id.endsWith(".$suffix") }
                services.studioRuntime.respond(permission, option.id)
            }
        }
    }

    private fun assertEffect(output: File, marker: String, decision: Decision) {
        if (decision == Decision.Deny || decision == Decision.Cancel) {
            assertFalse(output.exists(), "Declined or cancelled execution leaves no file")
        } else {
            assertTrue(output.isFile, "The model must actually execute the command")
            assertEquals(marker, output.readText())
        }
    }

    private suspend fun assertShellCall(
        services: AiEngineTestAccessors,
        target: EngineTarget,
        command: String,
        decision: Decision,
    ) {
        val summary = services.engineFacade.sessions.page(SessionQuery(engines = setOf(target.engine))).items.single()
        val features = services.engineFacade.sessions.get(summary.ref).features
        val history = assertIs<FeatureAccess.Available<SessionHistory>>(features.resolve(SessionHistory)).feature
        val calls = history.page(HistoryPageRequest(limit = 200)).items.filterIsInstance<SessionItem.ToolCall>()
        val call = calls.single { call ->
            val arguments = Json.parseToJsonElement(call.arguments).jsonObject
            arguments["command"]?.jsonPrimitive?.content == command
        }
        assertEquals(shellName(target), call.name)
        if (decision != Decision.Cancel) {
            assertEquals(
                if (decision == Decision.Deny) ToolCallStatus.Failed else ToolCallStatus.Succeeded,
                call.status,
                "The exact shell invocation has a terminal result",
            )
        }
    }

    private suspend fun connect(services: AiEngineTestAccessors, engine: EngineId, label: String): EngineTarget {
        val endpoint = URI(checkNotNull(System.getenv("HEARTBEAT_SECURITY_LIVE_ENDPOINT")))
        val scope = AuthScope(
            CompatibleProtocol.OpenAI.provider.id,
            EndpointOrigin("${endpoint.scheme}://${endpoint.rawAuthority}"),
            endpoint.path.trimEnd('/').takeIf { it.isNotEmpty() },
        )
        val keyFile = File(checkNotNull(System.getenv("HEARTBEAT_SECURITY_LIVE_KEY_FILE")))
        val key = Secret(keyFile.readText().trim().toCharArray())
        val source = try {
            services.engineAuthSources.addManagedKey(label, scope, key)
        } finally {
            key.close()
        }
        val facade = services.engineFacade
        facade.engines.state.first { engines -> engines.any { it.descriptor.id == engine } }
        facade.engines.refresh(engine)
        val binding = facade.bindings.connect(engine, source.info.id)
        val model = checkNotNull(System.getenv("HEARTBEAT_SECURITY_LIVE_MODEL"))
        return facade.models.refresh(engine, binding.id).models.first {
            it.target.model.value == model || it.target.model.value == "openai-compatible/$model"
        }.target
    }

    private fun phase(message: String) {
        System.getProperty("heartbeat.security.liveReport")?.let { File(it).appendText("$message\n") }
    }

    private fun shellName(target: EngineTarget): String = if (target.engine == PiEngineId) "bash" else "run_command"

    private enum class Decision { NoPrompt, Allow, Deny, SwitchToTrust, Cancel }
}
