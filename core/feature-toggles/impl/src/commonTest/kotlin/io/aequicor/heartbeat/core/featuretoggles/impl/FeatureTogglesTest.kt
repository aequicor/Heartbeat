package io.aequicor.heartbeat.core.featuretoggles.impl

import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.ToggleSource
import io.aequicor.heartbeat.core.featuretoggles.ToggleState
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.LogSink
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FeatureTogglesTest {

    private val streaming = FeatureToggle.Flag("chat.streaming", "Stream responses")
    private val mode = FeatureToggle.Choice("ai.mode", "Mode", listOf("fast", "smart"))
    private val beta = FeatureToggle.Flag("ai.beta", "Beta models", default = true)

    private val stores = FakeDataStores()
    private val store get() = stores.keyValue(KeyValueSpec("core_feature_toggles")) as FakeKeyValueStore
    private val logs = mutableListOf<String>()

    private fun build(vararg toggles: FeatureToggle<*>): Pair<DataStoreFeatureToggles, FeatureToggleControlImpl> {
        val registry = ToggleRegistry(toggles.toSet())
        val overrides = ToggleOverrides(stores, registry)
        return DataStoreFeatureToggles(registry, overrides) to FeatureToggleControlImpl(registry, overrides)
    }

    private fun logged(prefix: String) = logs.filter { it.startsWith(prefix) }

    @BeforeTest
    fun setUp() {
        Log.init(isDebug = true, sinks = listOf(LogSink { level, tag, _, message -> logs += "$level $tag $message" }))
    }

    @AfterTest
    fun tearDown() = Log.init(isDebug = false)

    @Test
    fun `a toggle without an override has its default`() = runTest {
        val (toggles, _) = build(streaming, mode, beta)

        assertEquals(false, toggles.get(streaming))
        assertEquals("fast", toggles.get(mode))
        assertEquals(true, toggles.observe(beta).first())
        assertEquals(listOf("INFO FT no local overrides"), logged("INFO FT no local"))
    }

    @Test
    fun `an override wins until it is reset`() = runTest {
        val (toggles, control) = build(streaming)
        val seen = collect(toggles.observe(streaming))

        control.setOverride(streaming, true)
        assertEquals(true, toggles.get(streaming))
        control.reset(streaming)

        assertEquals(listOf(false, true, false), seen)
        assertEquals(
            listOf(
                "INFO FT chat.streaming: false (Default) -> true (LocalOverride)",
                "INFO FT chat.streaming: true (LocalOverride) -> false (Default)",
            ),
            logged("INFO FT chat.streaming:"),
        )
    }

    @Test
    fun `an override equal to the default is still an override`() = runTest {
        val (_, control) = build(beta)

        control.setOverride(beta, true)

        assertEquals(listOf(ToggleState(beta, true, ToggleSource.LocalOverride)), control.observeStates().first())
    }

    @Test
    fun `states list every registered toggle by owner and key`() = runTest {
        val (_, control) = build(streaming, mode, beta)
        control.setOverride(mode, "smart")

        assertEquals(listOf(beta, mode, streaming), control.registered)
        assertEquals(
            listOf(
                ToggleState(beta, true, ToggleSource.Default),
                ToggleState(mode, "smart", ToggleSource.LocalOverride),
                ToggleState(streaming, false, ToggleSource.Default),
            ),
            control.observeStates().first(),
        )
    }

    @Test
    fun `states follow changes`() = runTest {
        val (_, control) = build(streaming)
        val seen = collect(control.observeStates())

        control.setOverride(streaming, true)

        assertEquals(listOf(false, true), seen.map { it.single().value })
    }

    @Test
    fun `no registered toggles give an empty state list`() = runTest {
        val (_, control) = build()

        assertEquals(emptyList(), control.observeStates().first())
    }

    @Test
    fun `reset all removes every override`() = runTest {
        val (toggles, control) = build(streaming, mode)
        control.setOverride(streaming, true)
        control.setOverride(mode, "smart")

        control.resetAll()

        assertEquals(false, toggles.get(streaming))
        assertEquals("fast", toggles.get(mode))
        assertEquals(emptyMap(), store.values.value)
        assertEquals(
            listOf("INFO FT reset all: 2 overrides of registered toggles removed, stale keys cleared"),
            logged("INFO FT reset all"),
        )
    }

    @Test
    fun `overrides are read at startup and logged once`() = runTest {
        build(streaming).second.setOverride(streaming, true)
        logs.clear()

        val (toggles, _) = build(streaming) // a new session over the same storage
        assertEquals(true, toggles.get(streaming))
        toggles.get(streaming)

        assertEquals(listOf("INFO FT local overrides: chat.streaming=true"), logged("INFO FT local overrides"))
    }

    @Test
    fun `only registered toggles and allowed options can be overridden`() = runTest {
        val (_, control) = build(mode)

        assertFailsWith<IllegalArgumentException> { control.setOverride(streaming, true) }
        assertFailsWith<IllegalArgumentException> { control.reset(streaming) }
        assertFailsWith<IllegalArgumentException> { control.setOverride(mode, "slow") }
        assertFailsWith<IllegalArgumentException> { control.setOverride(mode.copy(default = "smart"), "smart") }
    }

    @Test
    fun `a stored option that no longer exists falls back to the default`() = runTest {
        val (toggles, _) = build(mode)
        store.values.value = mapOf("ai.mode" to "removed")

        assertEquals("fast", toggles.get(mode))
        assertEquals("fast", toggles.observe(mode).first())
        assertEquals(1, logged("WARNING FT ai.mode: stored override 'removed'").size)
    }

    @Test
    fun `a storage failure reads as the default for features and fails the control`() = runTest {
        val (toggles, control) = build(beta)
        store.readFailure = IllegalStateException("broken file")

        assertEquals(true, toggles.get(beta))
        assertEquals(true, toggles.observe(beta).first())
        assertEquals(2, logged("WARNING FT ai.beta: overrides are unavailable").size)
        assertFailsWith<IllegalStateException> { control.setOverride(beta, false) }
    }

    @Test
    fun `reading an unregistered toggle works and warns once`() = runTest {
        val (toggles, _) = build(mode)

        assertEquals(false, toggles.get(streaming))
        toggles.get(streaming)
        toggles.observe(mode.copy(default = "smart"))

        assertEquals(
            listOf(
                "WARNING FT chat.streaming is read but not registered: invisible in the control panel",
                "WARNING FT ai.mode is read as ${mode.copy(default = "smart")}, registered as $mode",
            ),
            logged("WARNING FT"),
        )
    }

    @Test
    fun `one key with two declarations fails the registry`() {
        assertFailsWith<IllegalStateException> { ToggleRegistry(setOf(streaming, streaming.copy(default = true))) }
    }

    private fun <T> TestScope.collect(flow: Flow<T>): List<T> {
        val seen = mutableListOf<T>()
        flow.onEach { seen += it }.launchIn(backgroundScope + UnconfinedTestDispatcher(testScheduler))
        return seen
    }
}
