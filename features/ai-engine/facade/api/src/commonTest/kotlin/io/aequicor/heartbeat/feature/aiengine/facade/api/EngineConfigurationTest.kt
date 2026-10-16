package io.aequicor.heartbeat.feature.aiengine.facade.api

import io.aequicor.heartbeat.core.secrets.Secret
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.NativeImport
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.ProjectFile
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngineConfigurationTest {
    private val values = EngineConfigValues().write(TestConfig.proxy, JsonPrimitive("http://user:pass@proxy"))

    @Test
    fun `the unavailable service is off and refuses every operation`() = runTest {
        val service = UnavailableEngineConfiguration()
        assertEquals(EngineConfigurationState.Off, service.state.value)
        assertFalse(service.state.value.isEnabled)
        assertNull(service.state.value.engine(TestTarget.engine))
        val workspace = WorkspaceRef("project")
        val failures = listOf(
            assertFailsWith<ConfigException> {
                service.saveProfile(TestTarget.engine, ConfigEdit(values), ConfigRevision(0))
            },
            assertFailsWith<ConfigException> { service.readProject(TestTarget.engine, workspace) },
            assertFailsWith<ConfigException> {
                service.saveProject(TestTarget.engine, workspace, values, ProjectRevision())
            },
            assertFailsWith<ConfigException> { service.projectTrust(TestTarget.engine, workspace) },
            assertFailsWith<ConfigException> {
                service.setProjectTrust(TestTarget.engine, workspace, ProjectTrust.Trusted)
            },
            assertFailsWith<ConfigException> { service.previewImport(TestTarget.engine) },
            assertFailsWith<ConfigException> {
                service.importNative(TestTarget.engine, ImportSelection(emptySet()), ConfigRevision(0))
            },
        )
        assertTrue(failures.all { it.failure == ConfigFailure.Unsupported })
    }

    @Test
    fun `configuration carriers never print values or secrets`() {
        val secret = Secret("token-value".toCharArray())
        val printed = listOf(
            ConfigEdit(values, mapOf("SLOT" to secret)).toString(),
            ProjectConfig(
                TestTarget.engine,
                WorkspaceRef("project"),
                values,
                ProjectRevision(),
                unreadable = mapOf(TestConfig.settings to "{broken pass"),
            ).toString(),
            NativeImport(values, mapOf("SLOT" to secret)).toString(),
            ProjectFile("pass", "abc").toString(),
            ImportPreview("/Users/someone/.pi/agent", emptyList()).toString(),
            LaunchContext(config = values, trust = mapOf(WorkspaceRef("project") to ProjectTrust.Trusted)).toString(),
        )
        printed.forEach { text ->
            assertFalse("pass" in text, text)
            assertFalse("token-value" in text, text)
            assertFalse("someone" in text, text)
        }
        secret.close()
    }

    @Test
    fun `launch context compares configuration structurally`() {
        val first = LaunchContext(config = values)
        val second = LaunchContext(
            config = EngineConfigValues().write(TestConfig.proxy, JsonPrimitive("http://user:pass@proxy")),
        )
        assertEquals(first, second)
        assertFalse(first == first.copy(config = values.copy(secrets = mapOf("SLOT" to 2L))))
        assertFalse(first == first.copy(trust = mapOf(WorkspaceRef("project") to ProjectTrust.Content)))
        assertTrue(LaunchContext().config.isEmpty)
    }

    @Test
    fun `configuration failures carry their reason`() {
        val problem = ConfigProblem(TestConfig.settings, listOf("retry"), ConfigProblemReason.WrongType)
        val failure = assertFailsWith<ConfigException> { throw ConfigException(ConfigFailure.Invalid(listOf(problem))) }
        assertEquals(ConfigFailure.Invalid(listOf(problem)), failure.failure)
    }
}
