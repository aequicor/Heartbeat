package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagedInstall
import io.aequicor.heartbeat.feature.aiengine.facade.api.RuntimePreference
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class PiCandidateMetadataTest {
    @Test
    fun `custom executable is only examined and never executed`() {
        val directory = Files.createTempDirectory("pi-metadata-")
        try {
            val marker = directory.resolve("ran")
            val executable = directory.resolve("pi")
            Files.writeString(executable, "#!/bin/sh\ntouch '$marker'\n")
            assertTrue(executable.toFile().setExecutable(true))
            val launch = LaunchContext(LaunchSettings(executable = executable.toString()))
            val result = assertNotNull(inspectPiCandidateMetadata(launch))
            assertEquals(InstallSource.Custom, result.installation.source)
            assertEquals(executable.toString(), result.installation.path)
            assertNull(result.installation.version)
            assertNull(result.compatibility)
            assertFalse(Files.exists(marker))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `managed selection never falls back and version stays unproven`() {
        val directory = Files.createTempDirectory("pi-managed-metadata-")
        try {
            val executable = directory.resolve("pi")
            Files.writeString(executable, "binary")
            assertTrue(executable.toFile().setExecutable(true))
            val managed = ManagedInstall("1.0.0", executable.toString(), Instant.fromEpochSeconds(0))
            val selection = LaunchContext(LaunchSettings(runtime = RuntimePreference.Managed), managed)
            val result = assertNotNull(inspectPiCandidateMetadata(selection))
            assertEquals(InstallSource.Managed, result.installation.source)
            assertEquals(managed.version, result.installation.version)
            assertNull(result.compatibility)
            assertTrue(executable.toFile().setExecutable(false, false))
            val notExecutable = assertNotNull(inspectPiCandidateMetadata(selection))
            assertEquals(InstallSource.Missing, notExecutable.installation.source)
            Files.delete(executable)
            val missing = assertNotNull(inspectPiCandidateMetadata(selection))
            assertEquals(InstallSource.Missing, missing.installation.source)
            assertFalse(missing.installation.isRunnable)
            assertNull(missing.compatibility)
            assertNull(inspectPiCandidateMetadata(LaunchContext()))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
