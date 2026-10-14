package io.aequicor.heartbeat.core.secrets.impl.data

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinuxKeyringTest {
    @Test
    fun binarySnapshotIsStoredAndBorrowedBuffersAreErased() {
        val session = FakeSession(byteArrayOf(3, 0, -1))
        val next = byteArrayOf(7, 0, -2)
        val vault = LinuxKeyring("service") { service, profile ->
            assertEquals("service", service)
            assertEquals("profile", profile)
            session
        }

        val result = vault.transaction("profile", false) { old ->
            assertContentEquals(byteArrayOf(3, 0, -1), old)
            VaultUpdate(next, true, "committed")
        }

        assertEquals("committed", result)
        assertContentEquals(byteArrayOf(7, 0, -2), session.written)
        assertContentEquals(byteArrayOf(0, 0, 0), next)
        assertContentEquals(byteArrayOf(0, 0, 0), session.old)
        assertTrue(session.closed)
    }

    @Test
    fun lockedOrMissingKeyringNeverCallsTheMutation() {
        for (problem in VaultAccessProblem.entries) {
            var called = false
            val vault = LinuxKeyring("service") { _, _ -> throw ProtectedVaultAccessException(problem) }

            val error = assertFailsWith<ProtectedVaultAccessException> {
                vault.transaction("profile", false) {
                    called = true
                    VaultUpdate(byteArrayOf(1), true, Unit)
                }
            }

            assertEquals(problem.description, error.message)
            assertFalse(called)
        }
    }

    @Test
    fun missingNativeLibraryFailsWithoutFallingBackToLocalFiles() {
        val vault = LinuxKeyring("service") { _, _ -> throw UnsatisfiedLinkError("native diagnostic") }

        val error = assertFailsWith<ProtectedVaultAccessException> {
            vault.transaction("profile", false) { error("Must not access a fallback backend") }
        }

        assertEquals(VaultAccessProblem.LinuxKeyringUnavailable.description, error.message)
        assertNull(error.cause)
    }

    @Test
    fun failedWriteClearsBothSnapshotsAndDoesNotReturnTheResult() {
        val session = FakeSession(byteArrayOf(3)).apply { failWrite = true }
        val next = byteArrayOf(7)
        val vault = LinuxKeyring("service") { _, _ -> session }

        assertFailsWith<ProtectedVaultAccessException> {
            vault.transaction("profile", false) { VaultUpdate(next, true, Unit) }
        }

        assertContentEquals(byteArrayOf(0), session.old)
        assertContentEquals(byteArrayOf(0), next)
        assertNull(session.written)
        assertTrue(session.closed)
    }

    @Test
    fun failedReadClosesSessionWithoutInvokingTheCallback() {
        val session = FakeSession(null).apply { failRead = true }
        val vault = LinuxKeyring("service") { _, _ -> session }

        assertFailsWith<ProtectedVaultAccessException> {
            vault.transaction("profile", false) { error("Must not treat read failure as a missing value") }
        }

        assertEquals(0, session.writes)
        assertTrue(session.closed)
    }

    @Test
    fun failedCallbackClearsReadSnapshotAndDoesNotWrite() {
        val session = FakeSession(byteArrayOf(3))
        val vault = LinuxKeyring("service") { _, _ -> session }

        assertFailsWith<IllegalArgumentException> {
            vault.transaction("profile", false) { throw IllegalArgumentException("callback failed") }
        }

        assertContentEquals(byteArrayOf(0), session.old)
        assertEquals(0, session.writes)
        assertTrue(session.closed)
    }

    @Test
    fun explicitEraseSkipsReadingAndDeletesEvenWhenContentCannotBeRead() {
        val session = FakeSession(null).apply { failRead = true }
        val vault = LinuxKeyring("service") { _, _ -> session }

        vault.transaction("profile", true) {
            assertNull(it)
            VaultUpdate(null, true, Unit)
        }

        assertEquals(1, session.writes)
        assertNull(session.written)
        assertTrue(session.closed)
    }

    @Test
    fun unchangedTransactionDoesNotWriteToTheKeyring() {
        val session = FakeSession(null)
        val vault = LinuxKeyring("service") { _, _ -> session }

        vault.transaction("profile", false) { VaultUpdate(null, false, Unit) }

        assertEquals(0, session.writes)
        assertTrue(session.closed)
    }

    private class FakeSession(val old: ByteArray?) : LinuxKeyringSession {
        var failRead = false
        var failWrite = false
        var closed = false
        var writes = 0
        var written: ByteArray? = null

        override fun read(): ByteArray? {
            if (failRead) throw ProtectedVaultAccessException(VaultAccessProblem.LinuxKeyringLocked)
            return old
        }

        override fun write(bytes: ByteArray?) {
            if (failWrite) throw ProtectedVaultAccessException(VaultAccessProblem.LinuxKeyringLocked)
            writes++
            written = bytes?.copyOf()
        }

        override fun close() {
            closed = true
        }
    }
}
