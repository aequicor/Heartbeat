package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagedInstall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class PiManagedDigestTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `matching digest keeps the managed copy trusted`() = runTest {
        val file = executable("#!/bin/sh\nexit 0\n")

        assertEquals(true, verifyPiManagedCopy(managed(file, sha256(file)), Dispatchers.Unconfined))
    }

    @Test
    fun `a changed or missing copy does not match its pinned digest`() = runTest {
        val file = executable("#!/bin/sh\nexit 0\n")
        val pinned = sha256(file)

        file.writeText("#!/bin/sh\nexit 1\n")
        assertEquals(false, verifyPiManagedCopy(managed(file, pinned), Dispatchers.Unconfined))
        file.delete()
        assertEquals(false, verifyPiManagedCopy(managed(file, pinned), Dispatchers.Unconfined))
    }

    @Test
    fun `a copy without a recorded digest stays unknown`() = runTest {
        assertNull(verifyPiManagedCopy(managed(executable("#!/bin/sh\n"), null), Dispatchers.Unconfined))
    }

    private fun executable(content: String): File = temporaryFolder.newFile("pi").apply {
        writeText(content)
        check(setExecutable(true))
    }

    private fun managed(file: File, digest: String?): ManagedInstall =
        ManagedInstall("1.0.0", file.absolutePath, Instant.fromEpochSeconds(0), digest)

    private fun sha256(file: File): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file.readBytes()))
}
