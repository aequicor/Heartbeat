package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PiPendingDownloadTest {
    @Test
    fun `a preparable bundled runtime that is not on disk yet is pending`() {
        assertTrue(isPiPendingDownload(InstallSource.Bundled, Path.of("missing", "pi"), isPreparable = true))
        assertTrue(isPiPendingDownload(InstallSource.Bundled, executable = null, isPreparable = true))
    }

    @Test
    fun `a bundled runtime already on disk is not pending`() {
        val file = Files.createTempFile("pi-pending", "")
        try {
            assertFalse(isPiPendingDownload(InstallSource.Bundled, file, isPreparable = true))
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `only the bundled source can be pending and only while preparable`() {
        val missing = Path.of("missing", "pi")
        assertFalse(isPiPendingDownload(InstallSource.Bundled, missing, isPreparable = false))
        assertFalse(isPiPendingDownload(InstallSource.Custom, missing, isPreparable = true))
        assertFalse(isPiPendingDownload(InstallSource.Managed, missing, isPreparable = true))
        assertFalse(isPiPendingDownload(InstallSource.Missing, missing, isPreparable = true))
    }
}
