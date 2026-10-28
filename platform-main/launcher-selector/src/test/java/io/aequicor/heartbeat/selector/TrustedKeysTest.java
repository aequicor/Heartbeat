package io.aequicor.heartbeat.selector;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class TrustedKeysTest {
    static final byte[] MESSAGE = "message".getBytes(StandardCharsets.UTF_8);

    static String sign(KeyPair key, byte[] message) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(key.getPrivate());
        signer.update(message);
        return Base64.getEncoder().encodeToString(signer.sign());
    }

    @Test
    void anyOfTheTrustedKeysIsEnough() throws Exception {
        KeyPair primary = TestSigning.newKeyPair();
        KeyPair backup = TestSigning.newKeyPair();
        TrustedKeys keys = TrustedKeys.parse("# keys\n" + TestSigning.publicKeyLine(primary) + "\n\n"
            + TestSigning.publicKeyLine(backup) + "\n");
        assertTrue(keys.verifies(MESSAGE, sign(primary, MESSAGE)));
        assertTrue(keys.verifies(MESSAGE, sign(backup, MESSAGE)));
    }

    @Test
    void foreignKeyAndChangedMessageAreNotTrusted() throws Exception {
        KeyPair primary = TestSigning.newKeyPair();
        TrustedKeys keys = TrustedKeys.parse(TestSigning.publicKeyLine(primary));
        assertFalse(keys.verifies(MESSAGE, sign(TestSigning.newKeyPair(), MESSAGE)));
        assertFalse(keys.verifies("other".getBytes(StandardCharsets.UTF_8), sign(primary, MESSAGE)));
        assertFalse(keys.verifies(MESSAGE, "not base64!"));
        assertFalse(keys.verifies(MESSAGE, Base64.getEncoder().encodeToString(new byte[10])));
    }

    @Test
    void withoutKeysNothingIsTrusted() throws Exception {
        TrustedKeys keys = TrustedKeys.parse("# only comments\n");
        assertTrue(keys.isEmpty());
        assertFalse(keys.verifies(MESSAGE, sign(TestSigning.newKeyPair(), MESSAGE)));
    }

    @Test
    void invalidAndTooManyKeysFailInsteadOfTrustingAll() {
        assertThrows(IOException.class, () -> TrustedKeys.parse("not a key"));
        StringBuilder many = new StringBuilder();
        for (int i = 0; i <= TrustedKeys.MAX_KEYS; i++) {
            many.append(TestSigning.publicKeyLine(TestSigning.newKeyPair())).append('\n');
        }
        assertThrows(IOException.class, () -> TrustedKeys.parse(many.toString()));
    }

    @Test
    void builtInKeysAreReadable() throws Exception {
        // the committed file has no keys until the owner fills it; it must parse and trust nothing meanwhile
        TrustedKeys.builtIn();
    }
}
