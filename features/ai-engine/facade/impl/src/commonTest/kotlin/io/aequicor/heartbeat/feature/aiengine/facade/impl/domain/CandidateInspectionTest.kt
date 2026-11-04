package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCandidateInspection
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.Installation
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagedInstall
import io.aequicor.heartbeat.feature.aiengine.facade.api.RuntimePreference
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class CandidateInspectionTest {
    private val engine = EngineId("engine")
    private val managed = ManagedInstall("1.2.0", "/managed/engine", Instant.fromEpochSeconds(0))
    private val settings = LaunchSettings(runtime = RuntimePreference.Managed)

    @Test
    fun `managed candidate must be the selected managed path and version`() {
        val exact = EngineCandidateInspection(
            engine,
            Installation(InstallSource.Managed, "1.2.0", managed.executable),
            null,
        )
        validateCandidate(engine, settings, managed, exact)
        listOf(
            exact.copy(installation = exact.installation.copy(path = "/other/engine")),
            exact.copy(installation = exact.installation.copy(version = "1.3.0")),
        ).forEach { result ->
            assertFailsWith<EngineException> { validateCandidate(engine, settings, managed, result) }
        }
        assertFailsWith<EngineException> { validateCandidate(engine, settings, null, exact) }
    }
}
