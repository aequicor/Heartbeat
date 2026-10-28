package io.aequicor.heartbeat.selector;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Доверенные публичные ключи Ed25519: подпись верна, если её сделал любой из них (основной и запасной). Ключи зашиты в
 * селектор ({@code trusted-keys.txt}), поэтому их нельзя заменить ни сетью, ни каталогом данных. Без ключей не верна
 * никакая подпись.
 */
final class TrustedKeys {
    /** Не больше ключей, чем нужно: основной, запасной и по одному на смену. */
    static final int MAX_KEYS = 4;

    private final List<PublicKey> keys;

    private TrustedKeys(List<PublicKey> keys) {
        this.keys = List.copyOf(keys);
    }

    static TrustedKeys of(List<PublicKey> keys) {
        return new TrustedKeys(keys);
    }

    /** Ключи, зашитые в селектор; нечитаемый ресурс или ключ — ошибка, а не «доверяем всем». */
    static TrustedKeys builtIn() throws IOException {
        try (InputStream in = TrustedKeys.class.getResourceAsStream("trusted-keys.txt")) {
            if (in == null) {
                throw new IOException("Resource trusted-keys.txt is missing");
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    static TrustedKeys parse(String text) throws IOException {
        List<PublicKey> keys = new ArrayList<>();
        try {
            KeyFactory factory = KeyFactory.getInstance("Ed25519");
            for (String line : text.split("\\R")) {
                String value = line.trim();
                if (value.isEmpty() || value.startsWith("#")) {
                    continue;
                }
                keys.add(factory.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(value))));
            }
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IOException("Trusted key is invalid: " + e.getMessage(), e);
        }
        if (keys.size() > MAX_KEYS) {
            throw new IOException("At most " + MAX_KEYS + " trusted keys are allowed, found " + keys.size());
        }
        return new TrustedKeys(keys);
    }

    boolean isEmpty() {
        return keys.isEmpty();
    }

    /** Подпись {@code signatureBase64} под {@code message} сделана одним из доверенных ключей. */
    boolean verifies(byte[] message, String signatureBase64) {
        byte[] signature;
        try {
            signature = Base64.getDecoder().decode(signatureBase64);
        } catch (IllegalArgumentException e) {
            return false;
        }
        for (PublicKey key : keys) {
            try {
                Signature verifier = Signature.getInstance("Ed25519");
                verifier.initVerify(key);
                verifier.update(message);
                if (verifier.verify(signature)) {
                    return true;
                }
            } catch (GeneralSecurityException e) {
                // a malformed signature or a key that cannot verify counts as "not signed by this key"
                continue;
            }
        }
        return false;
    }
}
