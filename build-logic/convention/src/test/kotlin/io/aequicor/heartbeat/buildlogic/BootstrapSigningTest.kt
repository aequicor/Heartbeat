package io.aequicor.heartbeat.buildlogic

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BootstrapSigningTest {
    private val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val encoder = Base64.getEncoder()
    private val secret = encoder.encodeToString(pair.private.encoded)
    private val trusted = encoder.encodeToString(pair.public.encoded)
    private val bytes = "signed bootstrap manifest".toByteArray()

    @Test
    fun `release packaging fails closed without its signing key`() {
        assertFailsWith<IllegalStateException> { signBootstrapManifest(bytes, null, listOf(trusted), false) }
        assertFailsWith<IllegalStateException> { signBootstrapManifest(bytes, "  ", listOf(trusted), false) }
    }

    @Test
    fun `a supplied key must match one of the pinned native keys`() {
        val unrelated = encoder.encodeToString(KeyPairGenerator.getInstance("Ed25519").generateKeyPair().public.encoded)
        assertFailsWith<IllegalStateException> { signBootstrapManifest(bytes, secret, listOf(unrelated), false) }
        assertFailsWith<IllegalStateException> { signBootstrapManifest(bytes, secret, listOf(unrelated), true) }
        val signed = signBootstrapManifest(bytes, secret, listOf(unrelated, trusted), false)
        assertNull(signed.ephemeralPublicKey)
        assertTrue(Signature.getInstance("Ed25519").run {
            initVerify(pair.public)
            update(bytes)
            verify(signed.signature)
        })
    }

    @Test
    fun `local one-off signing is explicit and returns only its public key`() {
        val signed = signBootstrapManifest(bytes, null, listOf(trusted), true)
        val publicKey = KeyFactory.getInstance("Ed25519").generatePublic(
            X509EncodedKeySpec(Base64.getDecoder().decode(checkNotNull(signed.ephemeralPublicKey))),
        )
        assertTrue(Signature.getInstance("Ed25519").run {
            initVerify(publicKey)
            update(bytes)
            verify(signed.signature)
        })
    }
}
