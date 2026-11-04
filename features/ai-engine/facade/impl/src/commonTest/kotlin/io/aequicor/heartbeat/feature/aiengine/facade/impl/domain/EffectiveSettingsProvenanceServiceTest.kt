package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthOwnerId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigCapability
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigDocumentId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigDocumentSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigEdit
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigField
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigFieldKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigFormat
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigPage
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigRevision
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigSection
import io.aequicor.heartbeat.feature.aiengine.facade.api.EffectiveResolution
import io.aequicor.heartbeat.feature.aiengine.facade.api.EffectiveSettingsRead
import io.aequicor.heartbeat.feature.aiengine.facade.api.EffectiveSettingsReport
import io.aequicor.heartbeat.feature.aiengine.facade.api.EffectiveUnknownReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigSchema
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigValues
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigurationState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineDescriptor
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFamily
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePlatform
import io.aequicor.heartbeat.feature.aiengine.facade.api.ImportPreview
import io.aequicor.heartbeat.feature.aiengine.facade.api.ImportReport
import io.aequicor.heartbeat.feature.aiengine.facade.api.ImportSelection
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalizedText
import io.aequicor.heartbeat.feature.aiengine.facade.api.MASKED_SETTING_VALUE
import io.aequicor.heartbeat.feature.aiengine.facade.api.ObservedValue
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectConfig
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectEdit
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectRevision
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectTrust
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectTrustStatus
import io.aequicor.heartbeat.feature.aiengine.facade.api.RouteKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.SettingsLayer
import io.aequicor.heartbeat.feature.aiengine.facade.api.SettingsProvenance
import io.aequicor.heartbeat.feature.aiengine.facade.api.SettingsSourceKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.UnavailableReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.UnreadableReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.secretReference
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EffectiveSettingsRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineConfigurator
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineDeclaredResolution
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineEffectiveSettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRegistration
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.ProjectFiles
import io.aequicor.heartbeat.feature.aiengine.facade.api.write
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EffectiveSettingsProvenanceServiceTest {
    private val engine = EngineId("layered")
    private val binding = EngineBindingId("binding")
    private val source = AuthSourceId("src-a")
    private val otherSource = AuthSourceId("src-b")
    private val workspace = WorkspaceRef("project")
    private val settings = ConfigDocumentId("settings")
    private val retries = ConfigBinding.JsonPath(settings, listOf("retry", "maxRetries"))
    private val enabled = ConfigBinding.JsonPath(settings, listOf("retry", "enabled"))
    private val environment = ConfigBinding.JsonPath(settings, listOf("env"))
    private val token = ConfigBinding.JsonPath(settings, listOf("env", "API_TOKEN"))
    private val schema = EngineConfigSchema(
        documents = listOf(
            ConfigDocumentSpec(
                settings,
                LocalizedText("Settings"),
                ConfigFormat.Json,
                ConfigScope.entries.toSet(),
                "settings.json",
            ),
        ),
        pages = listOf(
            ConfigPage(
                "retry",
                LocalizedText("Retry"),
                listOf(
                    ConfigSection(
                        "retry",
                        LocalizedText("Retry"),
                        listOf(
                            ConfigField(
                                "retries",
                                LocalizedText("Retries"),
                                retries,
                                ConfigFieldKind.Number(min = 0, max = 10, default = 3),
                            ),
                            ConfigField(
                                "enabled",
                                LocalizedText("Enabled"),
                                enabled,
                                ConfigFieldKind.Flag(isOnByDefault = true),
                            ),
                            ConfigField(
                                "environment",
                                LocalizedText("Environment"),
                                environment,
                                ConfigFieldKind.KeyValues(isSecretAllowed = true),
                            ),
                            ConfigField("token", LocalizedText("Token"), token, ConfigFieldKind.Text()),
                        ),
                    ),
                ),
            ),
        ),
        capabilities = setOf(ConfigCapability.ProjectTrust),
    )
    private val configuration = Configuration(engine, schema)
    private val files = Configurator()
    private val bindings = MutableStateFlow<List<EngineBinding>>(emptyList())
    private val sources = MutableStateFlow<List<AuthSource>>(emptyList())

    @Test
    fun `profile value engine report and trusted project stay separate layers`() = runTest {
        val report = layeredReport()

        val retry = requireNotNull(report.surface("retries"))
        assertEquals(
            SettingsProvenance.Observed(ObservedValue.Literal(JsonPrimitive(7)), SettingsSourceKind.HeartbeatProfile),
            retry.heartbeat.provenance,
        )
        assertEquals(
            SettingsProvenance.Observed(ObservedValue.Literal(JsonPrimitive(11)), SettingsSourceKind.EngineEffective),
            retry.engine.provenance,
        )
        assertEquals(
            SettingsProvenance.Observed(ObservedValue.Literal(JsonPrimitive(9)), SettingsSourceKind.ProjectFile),
            retry.project.provenance,
        )
        assertEquals(
            EffectiveResolution.Declared(
                ObservedValue.Literal(JsonPrimitive(11)),
                setOf(SettingsLayer.Heartbeat, SettingsLayer.Engine, SettingsLayer.Project),
            ),
            retry.effective,
        )
        assertEquals(ConfigRevision(2), report.profileRevision)
        assertEquals(ProjectRevision(mapOf(settings to "project-1")), report.projectRevision)
        assertEquals(1, files.calls)
        assertEquals(1, configuration.projectReads)
    }

    @Test
    fun `a documented default is reported when the profile stores no value`() = runTest {
        val report = layeredReport()

        val enabledReport = requireNotNull(report.surface("enabled"))
        assertEquals(
            SettingsProvenance.Observed(
                ObservedValue.Literal(JsonPrimitive(true)),
                SettingsSourceKind.HeartbeatDefault,
            ),
            enabledReport.heartbeat.provenance,
        )
        assertEquals(SettingsProvenance.NotApplicable, enabledReport.project.provenance)
        assertEquals(
            EffectiveResolution.Unknown(EffectiveUnknownReason.SurfaceNotReported),
            enabledReport.effective,
        )
    }

    private suspend fun TestScope.layeredReport(): EffectiveSettingsReport {
        configuration.profile = EngineConfigValues().write(retries, JsonPrimitive(7))
        configuration.revision = ConfigRevision(2)
        configuration.trust = mapOf(workspace to ProjectTrust.Trusted)
        configuration.project = ProjectConfig(
            engine,
            workspace,
            EngineConfigValues().write(retries, JsonPrimitive(9)),
            ProjectRevision(mapOf(settings to "project-1")),
        )
        files.answer = {
            EngineEffectiveSettings(
                values = mapOf(
                    "retries" to ObservedValue.Literal(JsonPrimitive(11)),
                    "enabled" to ObservedValue.Literal(JsonPrimitive(false)),
                ),
                merged = mapOf(
                    "retries" to EngineDeclaredResolution(
                        ObservedValue.Literal(JsonPrimitive(11)),
                        setOf(SettingsLayer.Heartbeat, SettingsLayer.Engine, SettingsLayer.Project),
                    ),
                ),
            )
        }
        return observed(service().read(RouteKey(engine, binding), workspace))
    }

    @Test
    fun `an undecided project needs trust and is never read`() = runTest {
        configuration.profile = EngineConfigValues().write(retries, JsonPrimitive(7))
        files.answer = {
            EngineEffectiveSettings(
                values = mapOf("retries" to ObservedValue.Literal(JsonPrimitive(11))),
                merged = mapOf(
                    "retries" to EngineDeclaredResolution(
                        ObservedValue.Literal(JsonPrimitive(11)),
                        setOf(SettingsLayer.Engine, SettingsLayer.Project),
                    ),
                ),
            )
        }
        val report = observed(service().read(RouteKey(engine, binding), workspace))

        assertEquals(SettingsProvenance.NeedsTrust, requireNotNull(report.surface("retries")).project.provenance)
        assertEquals(0, configuration.projectReads)
        assertEquals(
            EffectiveResolution.Unknown(EffectiveUnknownReason.ProjectLayerNotObserved),
            requireNotNull(report.surface("retries")).effective,
        )
    }

    @Test
    fun `an unreadable project and an unavailable engine layer stay typed`() = runTest {
        configuration.trust = mapOf(workspace to ProjectTrust.Trusted)
        configuration.project = ProjectConfig(
            engine,
            workspace,
            EngineConfigValues(),
            ProjectRevision(mapOf(settings to "broken")),
            unreadable = mapOf(settings to "{ broken"),
        )
        files.answer = { null }
        val report = observed(service().read(RouteKey(engine, binding), workspace))

        assertTrue(
            report.surfaces.all {
                it.project.provenance == SettingsProvenance.Unreadable(UnreadableReason.InvalidDocument)
            },
        )
        assertTrue(
            report.surfaces.all {
                it.engine.provenance == SettingsProvenance.Unavailable(UnavailableReason.AdapterDoesNotReport)
            },
        )
        assertTrue(
            report.surfaces.all {
                it.effective == EffectiveResolution.Unknown(EffectiveUnknownReason.EngineLayerNotObserved)
            },
        )
    }

    @Test
    fun `an engine layer without a declared resolution stays unknown`() = runTest {
        files.answer = {
            EngineEffectiveSettings(values = mapOf("retries" to ObservedValue.Literal(JsonPrimitive(5))))
        }
        val report = observed(service().read(RouteKey(engine, binding)))

        assertEquals(
            SettingsProvenance.Observed(ObservedValue.Literal(JsonPrimitive(5)), SettingsSourceKind.EngineEffective),
            requireNotNull(report.surface("retries")).engine.provenance,
        )
        assertEquals(
            EffectiveResolution.Unknown(EffectiveUnknownReason.SurfaceNotReported),
            requireNotNull(report.surface("retries")).effective,
        )
        assertEquals(SettingsProvenance.NotApplicable, requireNotNull(report.surface("retries")).project.provenance)
    }

    @Test
    fun `secret typed values and secret named entries are masked`() = runTest {
        configuration.profile = EngineConfigValues()
            .write(
                environment,
                buildJsonObject {
                    put("API_TOKEN", "plain-secret")
                    put("OTHER_TOKEN", secretReference("TOKEN"))
                    put("MODE", "fast")
                },
            )
        files.answer = { EngineEffectiveSettings() }
        val report = observed(service().read(RouteKey(engine, binding)))

        assertEquals(
            SettingsProvenance.Observed(ObservedValue.Secret, SettingsSourceKind.HeartbeatProfile),
            requireNotNull(report.surface("token")).heartbeat.provenance,
        )
        val entries = requireNotNull(report.surface("environment")).heartbeat.provenance
        val observedEntries = entries as SettingsProvenance.Observed
        assertEquals(
            ObservedValue.Literal(
                buildJsonObject {
                    put("API_TOKEN", MASKED_SETTING_VALUE)
                    put("OTHER_TOKEN", MASKED_SETTING_VALUE)
                    put("MODE", "fast")
                },
            ),
            observedEntries.value,
        )
        assertFalse(observedEntries.value.toString().contains("plain-secret"))
        assertFalse(observedEntries.value.toString().contains("HEARTBEAT_SECRET"))
    }

    @Test
    fun `a binding change during the read discards the result`() = runTest {
        files.answer = { EngineEffectiveSettings() }
        val service = service()
        val route = RouteKey(engine, binding)
        observed(service.read(route))
        sources.value = sources.value + managedKey(id = otherSource.value, revision = AuthRevision.Known("r1"))
        files.beforeAnswer = { bindings.value = listOf(EngineBinding(binding, engine, otherSource)) }

        val second = service.read(route)
        runCurrent()

        assertEquals(EffectiveSettingsRead.Discarded, second)
        assertNull(service.report(route))
        assertEquals(2, files.calls)
    }

    @Test
    fun `a plain read of the published report performs no IO`() = runTest {
        configuration.trust = mapOf(workspace to ProjectTrust.Trusted)
        configuration.project = ProjectConfig(engine, workspace, EngineConfigValues(), ProjectRevision())
        files.answer = { EngineEffectiveSettings() }
        val service = service()
        val route = RouteKey(engine, binding)

        assertNull(service.report(route, workspace))
        assertEquals(0, files.calls)
        assertEquals(0, configuration.projectReads)

        observed(service.read(route, workspace))
        val engineCalls = files.calls
        val projectCalls = configuration.projectReads
        assertNotNull(service.report(route, workspace))
        assertNotNull(service.reports.value.keys.firstOrNull { it.workspace == workspace })
        assertEquals(engineCalls, files.calls)
        assertEquals(projectCalls, configuration.projectReads)
    }

    private fun observed(read: EffectiveSettingsRead): EffectiveSettingsReport =
        (read as? EffectiveSettingsRead.Observed)?.report ?: error("not observed: $read")

    private fun TestScope.service(): EffectiveSettingsProvenanceService {
        configuration.publish()
        bindings.value = listOf(EngineBinding(binding, engine, source))
        sources.value = listOf(managedKey(id = source.value, revision = AuthRevision.Known("r1")))
        return EffectiveSettingsProvenanceService(configuration, registry(), bindings, sources, facadeContext())
    }

    private fun registry() = EngineRegistry(
        listOf(
            EngineRegistration(
                EngineDescriptor(
                    engine,
                    "Layered",
                    EngineFamily.Vendor,
                    EnginePlatform.entries.toSet(),
                    FeatureToggle.Flag("engine.layered", "Layered"),
                ),
                AuthOwnerId("layered"),
                lazyOf(FakeEngineFactory()),
                configuration = schema,
                configurator = lazyOf(files),
            ),
        ),
        EnginePlatform.DesktopMacOs,
    )

    private class Configuration(private val engine: EngineId, private val schema: EngineConfigSchema) :
        EngineConfiguration {
        var profile = EngineConfigValues()
        var revision = ConfigRevision(1)
        var trust: Map<WorkspaceRef, ProjectTrust> = emptyMap()
        var projectReads = 0
        var project = ProjectConfig(engine, WorkspaceRef("unset"), EngineConfigValues(), ProjectRevision())
        private val stateFlow = MutableStateFlow(state())

        override val state: StateFlow<EngineConfigurationState> = stateFlow

        fun publish() {
            stateFlow.value = state()
        }

        override suspend fun readProject(engine: EngineId, workspace: WorkspaceRef): ProjectConfig {
            projectReads++
            return project.copy(engine = engine, workspace = workspace)
        }

        override suspend fun saveProfile(engine: EngineId, edit: ConfigEdit, expected: ConfigRevision): ConfigRevision =
            error("unused")

        override suspend fun saveProject(
            engine: EngineId,
            workspace: WorkspaceRef,
            edit: ProjectEdit,
            expected: ProjectRevision,
        ): ProjectConfig = error("unused")

        override suspend fun projectTrust(engine: EngineId, workspace: WorkspaceRef): ProjectTrustStatus =
            error("unused")

        override suspend fun setProjectTrust(engine: EngineId, workspace: WorkspaceRef, trust: ProjectTrust) = Unit

        override suspend fun previewImport(engine: EngineId): ImportPreview = error("unused")

        override suspend fun importNative(
            engine: EngineId,
            selection: ImportSelection,
            expected: ConfigRevision,
        ): ImportReport = error("unused")

        private fun state() = EngineConfigurationState(
            isEnabled = true,
            engines = listOf(EngineConfigState(engine, schema, profile, revision, trust = trust)),
        )
    }

    private class Configurator : EngineConfigurator {
        var answer: (EffectiveSettingsRequest) -> EngineEffectiveSettings? = { null }
        var beforeAnswer: suspend () -> Unit = {}
        var calls = 0
        val requests = mutableListOf<EffectiveSettingsRequest>()

        override suspend fun effectiveSettings(request: EffectiveSettingsRequest): EngineEffectiveSettings? {
            calls++
            requests += request
            beforeAnswer()
            return answer(request)
        }

        override suspend fun readProject(directory: String): ProjectFiles = ProjectFiles()

        override suspend fun writeProject(
            directory: String,
            changes: Map<ConfigDocumentId, String?>,
            expected: Map<ConfigDocumentId, String?>,
        ): ProjectFiles = error("unused")
    }
}
