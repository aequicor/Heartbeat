package io.aequicor.heartbeat.feature.aiengine.connections.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCandidateInspection
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCommand
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineManagement
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineManagementState
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettingsSnapshot
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettingsToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class RuntimeFileSelectionTest {
    private val engine = EngineId("test")
    private val token = object : LaunchSettingsToken {}
    private val snapshot = LaunchSettingsSnapshot(LaunchSettings(homeDirectory = "/private/home"), token)

    @Test
    fun `selected file is inspected against snapshot taken before picker without saving`() = runTest {
        val calls = mutableListOf<String>()
        val management = management(calls)
        val picker = picker {
            calls += "pick"
            "/opt/engine"
        }

        val result = selectRuntimeFile(engine, management, picker) as RuntimeFileSelection.Selected

        assertEquals(listOf("snapshot", "pick", "inspect"), calls)
        assertSame(snapshot, result.expected)
        assertEquals(snapshot.settings.copy(executable = "/opt/engine"), result.settings)
        assertEquals(null, result.inspection)
    }

    @Test
    fun `cancelling or lacking picker performs no inspection or write`() = runTest {
        val calls = mutableListOf<String>()
        assertEquals(RuntimeFileSelection.Cancelled, selectRuntimeFile(engine, management(calls), picker { null }))
        assertEquals(listOf("snapshot"), calls)
        calls.clear()
        assertEquals(
            RuntimeFileSelection.Unavailable,
            selectRuntimeFile(engine, management(calls), picker(false) { null }),
        )
        assertEquals(emptyList(), calls)
    }

    @Test
    fun `changed snapshot is not replaced with a fresh token after picking`() = runTest {
        val calls = mutableListOf<String>()
        val management = management(calls, reject = true)
        assertFailsWith<IllegalStateException> { selectRuntimeFile(engine, management, picker { "/opt/engine" }) }
        assertEquals(listOf("snapshot", "inspect"), calls)
    }

    private fun picker(available: Boolean = true, choose: suspend () -> String?) = object : RuntimeFilePicker {
        override val isAvailable = available
        override suspend fun pick(): String? = choose()
    }

    private fun management(calls: MutableList<String>, reject: Boolean = false) = object : EngineManagement {
        override val state = MutableStateFlow(EngineManagementState.Off)
        override suspend fun execute(engine: EngineId, command: EngineCommand): Unit = error("unexpected write")
        override suspend fun snapshotLaunchSettings(engine: EngineId): LaunchSettingsSnapshot {
            calls += "snapshot"
            return snapshot
        }
        override suspend fun inspectCandidate(
            engine: EngineId,
            expected: LaunchSettingsSnapshot,
            candidate: LaunchSettings,
        ): EngineCandidateInspection? {
            calls += "inspect"
            assertSame(snapshot, expected)
            if (reject) error("changed")
            return null
        }
    }
}
