package io.aequicor.heartbeat.platform.nativelauncher.bootstrap

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** RFC 8032 test 1 also runs against the native libsodium implementation. */
class BootstrapCryptoVectorTest {
    @Test
    fun rfc8032EmptyMessageVerifiesAndTamperingFails() {
        val key = hex("302a300506032b6570032100d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
        val signature = hex(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155" +
                "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
        )
        assertTrue(BootstrapSignature.verify(ByteArray(0), signature, listOf(key)))
        assertFalse(BootstrapSignature.verify(byteArrayOf(1), signature, listOf(key)))
    }

    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
