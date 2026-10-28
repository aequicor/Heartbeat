package io.aequicor.heartbeat.buildlogic

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * The bytes that are signed for the launcher entry of the train: the same record the launcher selector verifies
 * (`SlotRecord.canonical()` of `:platform-main:launcher-selector`). Both sides are pinned by one test vector, so
 * changing a field or the order here without the selector makes every signed launcher unusable.
 */
internal fun launcherRecordCanonical(id: String, version: String, sha256: String, kind: String, launcherApi: Int): ByteArray =
    ("heartbeat-launcher-record-v1\nid=$id\nversion=$version\nsha256=$sha256\nkind=$kind\nlauncherApi=$launcherApi\n")
        .toByteArray(Charsets.UTF_8)

/**
 * Ed25519 signature of [message] in Base64 (64 bytes, 88 characters). [privateKeyPkcs8Base64] is the PKCS#8 private
 * key in Base64, as `openssl genpkey -algorithm ed25519 | openssl pkcs8 -topk8 -nocrypt` writes it without the PEM
 * frame; it comes from a CI secret and is never logged.
 */
internal fun signEd25519(privateKeyPkcs8Base64: String, message: ByteArray): String {
    val key = KeyFactory.getInstance("Ed25519")
        .generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKeyPkcs8Base64.trim())))
    val signer = Signature.getInstance("Ed25519")
    signer.initSign(key)
    signer.update(message)
    return Base64.getEncoder().encodeToString(signer.sign())
}
