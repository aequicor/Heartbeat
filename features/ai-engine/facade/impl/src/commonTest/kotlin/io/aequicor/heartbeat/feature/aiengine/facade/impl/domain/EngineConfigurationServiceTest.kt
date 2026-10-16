package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.core.secrets.Secret
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthOwnerId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigCapability
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigDocument
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigDocumentId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigDocumentSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigEdit
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigException
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigField
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigFieldKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigFormat
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigPage
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigProblemReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigRevision
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigSection
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigSchema
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigValues
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineDescriptor
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePlatform
import io.aequicor.heartbeat.feature.aiengine.facade.api.ImportMode
import io.aequicor.heartbeat.feature.aiengine.facade.api.ImportNote
import io.aequicor.heartbeat.feature.aiengine.facade.api.ImportNoteReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ImportPreview
import io.aequicor.heartbeat.feature.aiengine.facade.api.ImportSelection
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspace
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalizedText
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectEdit
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectRevision
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectTrust
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectTrustStatus
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.read
import io.aequicor.heartbeat.feature.aiengine.facade.api.secretReference
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineConfigurator
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRegistration
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.NativeImport
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.ProjectFile
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.ProjectFiles
import io.aequicor.heartbeat.feature.aiengine.facade.api.write
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.MemoryEnginePreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngineConfigurationServiceTest {
    private val settings = ConfigDocumentId("settings")
    private val agents = ConfigDocumentId("agents")
    private val retries = ConfigBinding.JsonPath(settings, listOf("retry", "maxRetries"))
    private val token = ConfigBinding.JsonPath(settings, listOf("env", "API_TOKEN"))
    private val workspace = WorkspaceRef("project")
    private val schema = EngineConfigSchema(
        documents = listOf(
            ConfigDocumentSpec(
                settings,
                LocalizedText("Settings"),
                ConfigFormat.Json,
                ConfigScope.entries.toSet(),
                "s.json",
            ),
            ConfigDocumentSpec(
                agents,
                LocalizedText("Agents"),
                ConfigFormat.Text,
                ConfigScope.entries.toSet(),
                "AGENTS.md",
            ),
        ),
        pages = listOf(
            ConfigPage(
                "page",
                LocalizedText("Page"),
                listOf(
                    ConfigSection(
                        "retry",
                        LocalizedText("Retry"),
                        listOf(
                            ConfigField("retries", LocalizedText("Retries"), retries, ConfigFieldKind.Number(0, 10)),
                        ),
                    ),
                ),
            ),
        ),
        capabilities = setOf(ConfigCapability.ImportNative, ConfigCapability.ProjectTrust, ConfigCapability.Secrets),
    )
    private val flags = Flags()
    private val preferences = MemoryEnginePreferences()
    private val secrets = Vault()
    private val workspaces = Workspaces()
    private val files = Files()
    private val registry = EngineRegistry(
        listOf(configurable(registration()), registration(id = EngineId("plain"), owner = AuthOwnerId("plain"))),
        EnginePlatform.DesktopMacOs,
    )

    @Test
    fun `the state lists configurable engines only while both toggles are on`() = runTest {
        val service = service()
        runCurrent()
        assertTrue(service.state.value.isEnabled)
        assertEquals(listOf(TestEngine), service.state.value.engines.map { it.engine })
        assertEquals(ConfigRevision(0), service.state.value.engine(TestEngine)?.revision)

        flags.configuration.value = false
        runCurrent()

        assertFalse(service.state.value.isEnabled)
        val error = assertFailsWith<ConfigException> { service.saveProfile(TestEngine, edit(), ConfigRevision(0)) }
        assertEquals(ConfigFailure.Unsupported, error.failure)
    }

    @Test
    fun `saving the profile checks the revision and the values`() = runTest {
        val service = service()
        runCurrent()

        assertEquals(ConfigRevision(1), service.saveProfile(TestEngine, edit(retry = 3), ConfigRevision(0)))
        runCurrent()

        assertEquals(JsonPrimitive(3), service.state.value.engine(TestEngine)?.profile?.read(retries))
        assertEquals(ConfigFailure.Conflict, failure { service.saveProfile(TestEngine, edit(), ConfigRevision(0)) })
        val invalid = failure { service.saveProfile(TestEngine, edit(retry = 11), ConfigRevision(1)) }
        assertEquals(
            listOf(ConfigProblemReason.OutOfRange),
            (invalid as ConfigFailure.Invalid).problems.map { it.reason },
        )
        assertEquals(
            ConfigFailure.Unsupported,
            failure { service.saveProfile(EngineId("plain"), edit(), ConfigRevision(0)) },
        )
    }

    @Test
    fun `secrets are stored before the values and unused ones are removed`() = runTest {
        val service = service()
        runCurrent()
        val reference = EngineConfigValues().write(token, JsonPrimitive(secretReference("TOKEN")))
        val secret = Secret("value".toCharArray())

        service.saveProfile(
            TestEngine,
            ConfigEdit(reference, mapOf("TOKEN" to secret, "UNUSED" to secret)),
            ConfigRevision(0),
        )

        assertEquals(listOf("put TOKEN"), secrets.calls)
        assertEquals(mapOf("TOKEN" to 1L), preferences.current.value.configOf(TestEngine).values.secrets)

        service.saveProfile(TestEngine, ConfigEdit(reference), ConfigRevision(1))
        assertEquals(mapOf("TOKEN" to 1L), preferences.current.value.configOf(TestEngine).values.secrets)

        service.saveProfile(TestEngine, edit(retry = 1), ConfigRevision(2))
        assertEquals(listOf("put TOKEN", "remove TOKEN"), secrets.calls)
        assertEquals(emptyMap(), preferences.current.value.configOf(TestEngine).values.secrets)
        secret.close()
    }

    @Test
    fun `a failed vault write leaves the configuration unchanged`() = runTest {
        val service = service()
        runCurrent()
        secrets.failure = IllegalStateException("backend")
        val reference = EngineConfigValues().write(token, JsonPrimitive(secretReference("TOKEN")))

        val failure = failure {
            service.saveProfile(
                TestEngine,
                ConfigEdit(reference, mapOf("TOKEN" to Secret(charArrayOf('x')))),
                ConfigRevision(0),
            )
        }

        assertEquals(ConfigFailure.Storage, failure)
        assertEquals(0, preferences.current.value.configOf(TestEngine).revision)
    }

    @Test
    fun `project files are parsed and unreadable ones kept as text`() = runTest {
        val service = service()
        runCurrent()
        files.files[agents] = ProjectFile("Be brief", "r1")
        files.files[settings] = ProjectFile("{ // comment\n}", "r2")

        val project = service.readProject(TestEngine, workspace)

        assertEquals(ConfigDocument.Text("Be brief"), project.values.documents[agents])
        assertEquals(mapOf(settings to "{ // comment\n}"), project.unreadable)
        assertEquals(ProjectRevision(mapOf(settings to "r2", agents to "r1")), project.revision)
        assertEquals("/work/project", files.directories.single())
    }

    @Test
    fun `saving a project writes changed documents and keeps unreadable ones`() = runTest {
        val service = service()
        runCurrent()
        files.files[agents] = ProjectFile("Old", "r1")
        files.files[settings] = ProjectFile("{ broken", "r2")
        val values = EngineConfigValues(mapOf(agents to ConfigDocument.Text("New")))

        service.saveProject(
            TestEngine,
            workspace,
            ProjectEdit(values),
            ProjectRevision(mapOf(agents to "r1", settings to "r2")),
        )

        assertEquals<List<Map<ConfigDocumentId, String?>>>(listOf(mapOf(agents to "New")), files.writes)
        assertEquals<Map<ConfigDocumentId, String?>>(mapOf(agents to "r1"), files.expected.single())
        assertEquals("{ broken", files.files.getValue(settings).text)

        service.saveProject(
            TestEngine,
            workspace,
            ProjectEdit(values, raw = mapOf(settings to "{}")),
            ProjectRevision(mapOf(agents to files.files.getValue(agents).revision, settings to "r2")),
        )
        assertEquals<Map<ConfigDocumentId, String?>>(mapOf(settings to "{}"), files.writes.last())
    }

    @Test
    fun `an emptied file that does not parse is removed`() = runTest {
        val service = service()
        runCurrent()
        files.files[settings] = ProjectFile("{ broken", "r2")

        service.saveProject(
            TestEngine,
            workspace,
            ProjectEdit(EngineConfigValues(), raw = mapOf(settings to "")),
            ProjectRevision(mapOf(settings to "r2")),
        )

        assertEquals<Map<ConfigDocumentId, String?>>(mapOf(settings to null), files.writes.single())
        assertFalse(settings in files.files)
    }

    @Test
    fun `a project save refuses changed files and vault references`() = runTest {
        val service = service()
        runCurrent()
        files.files[agents] = ProjectFile("Old", "r2")
        val values = EngineConfigValues(mapOf(agents to ConfigDocument.Text("New")))

        val conflict = failure {
            service.saveProject(
                TestEngine,
                workspace,
                ProjectEdit(values),
                ProjectRevision(mapOf(agents to "r1")),
            )
        }
        assertEquals(ConfigFailure.Conflict, conflict)

        val secret = EngineConfigValues().write(token, JsonPrimitive(secretReference("TOKEN")))
        val invalid = failure { service.saveProject(TestEngine, workspace, ProjectEdit(secret), ProjectRevision()) }
        assertEquals(
            listOf(ConfigProblemReason.SecretInProject),
            (invalid as ConfigFailure.Invalid).problems.map { it.reason },
        )
        val text = "{ \"t\": \"${secretReference("TOKEN")}\" }"
        val raw = ProjectEdit(EngineConfigValues(), raw = mapOf(settings to text))
        val rawInvalid = failure { service.saveProject(TestEngine, workspace, raw, ProjectRevision()) }
        assertEquals(
            listOf(ConfigProblemReason.SecretInProject),
            (rawInvalid as ConfigFailure.Invalid).problems.map { it.reason },
        )
        assertTrue(files.writes.isEmpty())
    }

    @Test
    fun `json documents are written formatted`() = runTest {
        val service = service()
        runCurrent()
        val values = EngineConfigValues().write(retries, JsonPrimitive(2))

        service.saveProject(TestEngine, workspace, ProjectEdit(values), ProjectRevision())

        assertEquals("{\n    \"retry\": {\n        \"maxRetries\": 2\n    }\n}\n", files.writes.single()[settings])
    }

    @Test
    fun `an unknown project is unavailable`() = runTest {
        val service = service()
        runCurrent()
        assertEquals(
            ConfigFailure.ProjectUnavailable,
            failure { service.readProject(TestEngine, WorkspaceRef("gone")) },
        )
    }

    @Test
    fun `trust decisions are saved per project and undecided removes them`() = runTest {
        val service = service()
        runCurrent()
        files.isGated = true
        assertEquals(ProjectTrustStatus(ProjectTrust.Undecided, true), service.projectTrust(TestEngine, workspace))

        service.setProjectTrust(TestEngine, workspace, ProjectTrust.Trusted)
        assertEquals(ProjectTrust.Trusted, service.projectTrust(TestEngine, workspace).trust)
        assertEquals(0, preferences.current.value.configOf(TestEngine).revision)

        service.setProjectTrust(TestEngine, workspace, ProjectTrust.Undecided)
        assertEquals(emptyMap(), preferences.current.value.configOf(TestEngine).trust)
    }

    @Test
    fun `an import merges documents and stores its secrets`() = runTest {
        val service = service()
        runCurrent()
        val enabled = ConfigBinding.JsonPath(settings, listOf("retry", "enabled"))
        service.saveProfile(
            TestEngine,
            ConfigEdit(edit(retry = 1).values.write(enabled, JsonPrimitive(true))),
            ConfigRevision(0),
        )
        val secret = Secret("token".toCharArray())
        val imported = EngineConfigValues()
            .write(retries, JsonPrimitive(5))
            .write(token, JsonPrimitive(secretReference("IMPORTED")))
        files.native = NativeImport(
            imported,
            mapOf("IMPORTED" to secret),
            listOf("settings"),
            listOf(ImportNote("settings", ImportNoteReason.OwnedKeyDropped, listOf("defaultModel"))),
        )

        val report = service.importNative(TestEngine, ImportSelection(setOf("settings")), ConfigRevision(1))

        val saved = preferences.current.value.configOf(TestEngine).values
        assertEquals(JsonPrimitive(5), saved.read(retries))
        assertEquals(JsonPrimitive(true), saved.read(enabled))
        assertEquals(mapOf("IMPORTED" to 2L), saved.secrets)
        assertEquals(listOf("settings"), report.imported)
        assertEquals(listOf("put IMPORTED"), secrets.calls)
        assertFailsWith<IllegalStateException> { secret.reveal { it.size } }
    }

    @Test
    fun `a stale import is refused before anything is read`() = runTest {
        val service = service()
        runCurrent()
        service.saveProfile(TestEngine, edit(retry = 1), ConfigRevision(0))

        val stale = failure { service.importNative(TestEngine, ImportSelection(setOf("settings")), ConfigRevision(0)) }

        assertEquals(ConfigFailure.Conflict, stale)
        assertEquals(0, files.nativeReads)
    }

    @Test
    fun `replace mode swaps whole documents`() {
        val current = EngineConfigValues().write(
            retries,
            JsonPrimitive(1),
        ).write(ConfigBinding.WholeDocument(agents), JsonPrimitive("A"))
        val imported = EngineConfigValues(mapOf(settings to ConfigDocument.Json(buildJsonObject { put("x", 1) })))

        val replaced = merge(current, imported, ImportMode.Replace)

        assertNull(replaced.read(retries))
        assertEquals(JsonPrimitive("A"), replaced.read(ConfigBinding.WholeDocument(agents)))
    }

    private fun edit(retry: Int = 3) = ConfigEdit(EngineConfigValues().write(retries, JsonPrimitive(retry)))

    private suspend fun failure(block: suspend () -> Unit): ConfigFailure =
        assertFailsWith<ConfigException> { block() }.failure

    private fun TestScope.service() =
        EngineConfigurationService(registry, flags, preferences, secrets, workspaces, facadeContext())

    private fun configurable(base: EngineRegistration) = EngineRegistration(
        base.descriptor,
        base.authOwner,
        base.factory,
        base.sessionSources,
        base.authenticators,
        configuration = schema,
        configurator = lazyOf(files),
    )

    private class Flags : EngineFlags {
        val configuration = MutableStateFlow(true)

        override fun management(): Flow<Boolean> = MutableStateFlow(true)

        override fun configuration(): Flow<Boolean> = configuration

        override fun developer(descriptor: EngineDescriptor): Flow<DeveloperFlags> = emptyFlow()
    }

    private class Vault : ConfigSecrets {
        val calls = mutableListOf<String>()
        var failure: Exception? = null

        override suspend fun put(engine: EngineId, slot: String, value: Secret) {
            failure?.let { throw it }
            calls += "put $slot"
        }

        override suspend fun remove(engine: EngineId, slot: String) {
            calls += "remove $slot"
        }
    }

    private class Workspaces : LocalWorkspaces {
        override val isAvailable: Boolean = true

        override fun observe(): Flow<List<LocalWorkspace>> = emptyFlow()

        override suspend fun register(directory: String): LocalWorkspace = error("unused")

        override suspend fun resolve(ref: WorkspaceRef): String? = "/work/${ref.value}".takeIf {
            ref.value == "project"
        }
    }

    private class Files : EngineConfigurator {
        val files = mutableMapOf<ConfigDocumentId, ProjectFile>()
        val directories = mutableListOf<String>()
        val writes = mutableListOf<Map<ConfigDocumentId, String?>>()
        val expected = mutableListOf<Map<ConfigDocumentId, String?>>()
        var isGated = false
        var native: NativeImport? = null
        private var revision = 0

        override suspend fun readProject(directory: String): ProjectFiles {
            directories += directory
            return ProjectFiles(files.toMap())
        }

        override suspend fun writeProject(
            directory: String,
            changes: Map<ConfigDocumentId, String?>,
            expected: Map<ConfigDocumentId, String?>,
        ): ProjectFiles {
            writes += changes
            this.expected += expected
            changes.forEach { (id, text) ->
                if (text == null) files.remove(id) else files[id] = ProjectFile(text, "w${++revision}")
            }
            return ProjectFiles(files.toMap())
        }

        override suspend fun hasGatedConfiguration(directory: String): Boolean = isGated

        override suspend fun previewNative(): ImportPreview = ImportPreview("~/.engine", emptyList())

        var nativeReads = 0

        override suspend fun readNative(selection: ImportSelection): NativeImport {
            nativeReads++
            return requireNotNull(native)
        }
    }
}
