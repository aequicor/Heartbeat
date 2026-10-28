package io.aequicor.heartbeat.selector;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.List;

/** Ключи и подписанные записи для тестов: подписывает так же, как будет подписывать CI. */
final class TestSigning {
    final KeyPair primary = newKeyPair();
    final KeyPair backup = newKeyPair();

    /** Доверенные ключи теста: основной и запасной. */
    TrustedKeys trusted() {
        return TrustedKeys.of(List.of(primary.getPublic(), backup.getPublic()));
    }

    static KeyPair newKeyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static String publicKeyLine(KeyPair pair) {
        return Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
    }

    /** Запись лаунчера, подписанная {@code key}. */
    static SlotRecord record(KeyPair key, String id, String version, String sha256, int level) {
        return sign(key, new SlotRecord(id, version, sha256, SlotRecord.KIND_LAUNCHER, level, "A".repeat(86) + "=="));
    }

    /** Та же запись с подписью {@code key} вместо заглушки. */
    static SlotRecord sign(KeyPair key, SlotRecord unsigned) {
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(key.getPrivate());
            signer.update(unsigned.canonical());
            return new SlotRecord(unsigned.id(), unsigned.version(), unsigned.sha256(), unsigned.kind(),
                unsigned.launcherApi(), Base64.getEncoder().encodeToString(signer.sign()));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Кладёт {@code jar} в слот и пишет подписанную {@code key} запись уровня {@code level}. */
    static SlotRecord install(SlotStore store, String slot, Path jar, KeyPair key, int level) throws IOException {
        Path target = store.jar(slot);
        Files.createDirectories(target.getParent());
        Files.copy(jar, target, StandardCopyOption.REPLACE_EXISTING);
        SlotRecord record = record(key, "launcher", "t" + level, SlotStore.sha256(target), level);
        record.write(store.slotDir(slot).resolve(SlotRecord.FILE_NAME));
        return record;
    }
}
