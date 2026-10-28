package io.aequicor.heartbeat.selector;

import java.io.IOException;
import java.nio.file.Files;

/**
 * Проверка слота перед запуском и перед активацией: запись подписана доверенным ключом, это запись лаунчера, SHA-256
 * jar совпадает с подписанным, а уровень лаунчера не ниже порога ({@link SlotState#floor}). Любой отказ означает, что
 * слот запускать нельзя; селектор отвергает его и берёт следующий.
 */
final class SlotVerifier {
    /** Слот не прошёл проверку; сообщение называет причину. */
    static final class VerificationException extends Exception {
        VerificationException(String message) {
            super(message);
        }

        VerificationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final TrustedKeys keys;

    SlotVerifier(TrustedKeys keys) {
        this.keys = keys;
    }

    /**
     * @param floor самый высокий уровень лаунчера из подтверждённых слотов; ниже него слот не принимается
     * @return проверенная запись слота
     */
    SlotRecord verify(SlotStore store, String slot, int floor) throws VerificationException {
        SlotRecord record;
        try {
            record = SlotRecord.read(store.slotDir(slot).resolve(SlotRecord.FILE_NAME));
        } catch (IOException e) {
            throw new VerificationException("Slot " + slot + " has no valid record: " + e.getMessage(), e);
        }
        if (!SlotRecord.KIND_LAUNCHER.equals(record.kind())) {
            throw new VerificationException("Slot " + slot + " holds a " + record.kind() + ", not a launcher");
        }
        if (!keys.verifies(record.canonical(), record.signature())) {
            throw new VerificationException("Slot " + slot + " is not signed by a trusted key");
        }
        if (record.launcherApi() < floor) {
            throw new VerificationException("Slot " + slot + " has launcher level " + record.launcherApi()
                + " below the confirmed level " + floor);
        }
        if (!Files.isRegularFile(store.jar(slot))) {
            throw new VerificationException("Slot " + slot + " has no jar");
        }
        try {
            String actual = SlotStore.sha256(store.jar(slot));
            if (!actual.equals(record.sha256())) {
                throw new VerificationException("Jar of slot " + slot + " does not match its signed record");
            }
        } catch (IOException e) {
            throw new VerificationException("Jar of slot " + slot + " is unreadable: " + e.getMessage(), e);
        }
        return record;
    }
}
