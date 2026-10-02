package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.core.datastore.Retention
import io.aequicor.heartbeat.core.datastore.StoreKey
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.aiengine.facade.api.AiEngines
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineDescriptor
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFamily
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineManagementEnabled
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePlatform
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnvironmentEntry
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagedInstall
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.InstallPlan
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.InstallStep
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.ManagedInstallStore
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.ProfileEnginePreferences
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.StagedInstall
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class EngineManagementStorageTest {
    private val engine = EngineId("codex")
    private val descriptor = EngineDescriptor(
        engine,
        "Codex",
        EngineFamily.Vendor,
        setOf(EnginePlatform.DesktopMacOs),
        FeatureToggle.Flag("ai.codex", "Codex", default = true),
    )
    private val settings = LaunchSettings(
        executable = "/opt/codex",
        environment = listOf(EnvironmentEntry("RUST_LOG", "info")),
    )

    @Test
    fun `a failed managed copy refresh preserves saved launch settings`() = runTest {
        val flags = Flags(mapOf(EngineManagementEnabled to true))
        val preferences = MemoryEnginePreferences(ProfileEnginePreferences(launch = mapOf(engine to settings)))
        val installs = Installs(emptyMap())
        installs.failure = io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementException(
            io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementFailure.Install(
                io.aequicor.heartbeat.feature.aiengine.facade.api.InstallFailureReason.Storage,
            ),
        )
        val config = ProfileLaunchConfig(flags, preferences, installs)
        assertEquals(LaunchContext(settings), config.context(engine))
        installs.failure = null
        assertEquals(LaunchContext(settings), config.context(engine))
        assertEquals(1, installs.refreshes)
    }

    @Test
    fun `preferences round trip and default launch settings are not stored`() = runTest {
        val store = MemoryStore()
        val storage = EngineManagementStorage(store)

        storage.update {
            it.copy(disabled = setOf(engine), launch = mapOf(engine to settings, EngineId("pi") to LaunchSettings()))
        }

        val loaded = EngineManagementStorage(store).load()
        assertEquals(setOf(engine), loaded.disabled)
        assertEquals(mapOf(engine to settings), loaded.launch)
        assertEquals(LaunchSettings(), loaded.launchOf(EngineId("pi")))
        assertEquals(loaded, storage.observe().first())
        assertEquals(ProfileEnginePreferences(), EngineManagementStorage(MemoryStore()).load())
    }

    @Test
    fun `the gate needs both developer flags and, with management on, the profile switch`() = runTest {
        data class Case(val catalog: Boolean, val own: Boolean, val management: Boolean, val userOff: Boolean)
        val cases = listOf(true, false).flatMap { catalog ->
            listOf(true, false).flatMap { own ->
                listOf(
                    true,
                    false,
                ).flatMap { management -> listOf(true, false).map { Case(catalog, own, management, it) } }
            }
        }
        cases.forEach { case ->
            val toggles = Flags(
                mapOf(
                    AiEngines to case.catalog,
                    descriptor.toggle to case.own,
                    EngineManagementEnabled to case.management,
                ),
            )
            val chosen = ProfileEnginePreferences(disabled = if (case.userOff) setOf(engine) else emptySet())
            val gate = ProfileEngineGate(toggles, MemoryEnginePreferences(chosen))
            val expected = case.catalog && case.own && !(case.management && case.userOff)

            assertEquals(expected, gate.isEnabled(descriptor), case.toString())
            assertEquals(expected, gate.observe(descriptor).first(), case.toString())
        }
    }

    @Test
    fun `switching an engine off in the profile reaches gate observers`() = runTest {
        val preferences = MemoryEnginePreferences()
        val gate = ProfileEngineGate(Flags(mapOf(EngineManagementEnabled to true)), preferences)
        assertTrue(gate.observe(descriptor).first())

        preferences.update { it.copy(disabled = setOf(engine)) }

        assertFalse(gate.observe(descriptor).first())
    }

    @Test
    fun `adapters get defaults while management is off and saved settings with the managed copy when on`() = runTest {
        val flags = Flags(mapOf(EngineManagementEnabled to false))
        val preferences = MemoryEnginePreferences(ProfileEnginePreferences(launch = mapOf(engine to settings)))
        val installs = Installs(mapOf(engine to ManagedInstall("1.0.0", "/data/codex", Instant.fromEpochSeconds(1))))
        val config = ProfileLaunchConfig(flags, preferences, installs)

        assertEquals(LaunchContext(), config.context(engine))
        assertEquals(0, installs.refreshes)

        flags.values[EngineManagementEnabled] = true
        assertEquals(LaunchContext(settings, installs.loaded.getValue(engine)), config.context(engine))
        assertEquals(LaunchContext(), config.context(EngineId("pi")))
        assertEquals(1, installs.refreshes)
    }

    private class Flags(initial: Map<FeatureToggle<*>, Any>) : FeatureToggles {
        val values = initial.toMutableMap()

        override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> = MutableStateFlow(value(toggle))

        override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = value(toggle)

        @Suppress("UNCHECKED_CAST") // Each test sets the value of the toggle's own type.
        private fun <T : Any> value(toggle: FeatureToggle<T>): T = values[toggle] as T? ?: toggle.default
    }

    private class Installs(val loaded: Map<EngineId, ManagedInstall>) : ManagedInstallStore {
        var refreshes = 0
        var failure: Exception? = null
        override val state = MutableStateFlow(emptyMap<EngineId, ManagedInstall>())

        override suspend fun refresh() {
            refreshes++
            failure?.let { throw it }
            state.value = loaded
        }

        override suspend fun stage(engine: EngineId, plan: InstallPlan, progress: suspend (InstallStep) -> Unit) =
            error("unused")

        override suspend fun activate(staged: StagedInstall) = error("unused")
        override suspend fun discard(staged: StagedInstall) = error("unused")
        override suspend fun uninstall(engine: EngineId) = error("unused")
    }

    private class MemoryStore : KeyValueStore {
        private val values = MutableStateFlow(emptyMap<String, Any>())
        override val spec = KeyValueSpec("aiengine_management")

        @Suppress("UNCHECKED_CAST") // The fake keeps values as typed by the caller's key.
        override fun <T : Any> observe(key: StoreKey<T>): Flow<T?> = values.map { it[key.name] as T? }

        @Suppress("UNCHECKED_CAST") // The fake keeps values as typed by the caller's key.
        override suspend fun <T : Any> get(key: StoreKey<T>): T? = values.value[key.name] as T?

        override suspend fun <T : Any> set(key: StoreKey<T>, value: T, retention: Retention) {
            values.value += key.name to value
        }

        override suspend fun remove(key: StoreKey<*>) {
            values.value -= key.name
        }

        override suspend fun clear() {
            values.value = emptyMap()
        }
    }
}
