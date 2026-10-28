package io.aequicor.heartbeat.selector;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;

/**
 * Проверка слота перед запуском и перед активацией: запись подписана доверенным ключом, это запись лаунчера, SHA-256
 * jar совпадает с подписанным, а уровень лаунчера не ниже порога ({@link SlotState#floor}). Отказ означает, что слот
 * запускать нельзя. {@link VerificationException} окончательный (подпись, тип, уровень, хэш, нет файла): селектор
 * отвергает слот. {@link UnreadableException} — слот сейчас не прочитать (антивирус, занятый файл на Windows): запись
 * может быть верной, поэтому слот пропускается на этот запуск и не отвергается.
 */
final class SlotVerifier {
    /** Слот не прошёл проверку; сообщение называет причину. */
    static class VerificationException extends Exception {
        VerificationException(String message) {
            super(message);
        }

        VerificationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Файл слота не прочитать сейчас; о самом слоте это ничего не говорит. */
    static final class UnreadableException extends VerificationException {
        UnreadableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final TrustedKeys keys;

    SlotVerifier(TrustedKeys keys) {
        this.keys = keys;
    }

    /**
     * Запись лаунчера допустима: это {@code kind=launcher}, подпись верна, уровень не ниже порога. Jar не проверяется.
     *
     * @param what как назвать проверяемое в сообщении об отказе
     */
    void verifyRecord(SlotRecord record, String what, int floor) throws VerificationException {
        if (!SlotRecord.KIND_LAUNCHER.equals(record.kind())) {
            throw new VerificationException(what + " holds a " + record.kind() + ", not a launcher");
        }
        if (!keys.verifies(record.canonical(), record.signature())) {
            throw new VerificationException(what + " is not signed by a trusted key");
        }
        if (record.launcherApi() < floor) {
            throw new VerificationException(what + " has launcher level " + record.launcherApi()
                + " below the confirmed level " + floor);
        }
    }

    /**
     * @param floor самый высокий уровень лаунчера из подтверждённых слотов; ниже него слот не принимается
     * @return проверенная запись слота
     */
    SlotRecord verify(SlotStore store, String slot, int floor) throws VerificationException {
        SlotRecord record;
        try {
            record = SlotRecord.read(store.slotDir(slot).resolve(SlotRecord.FILE_NAME));
        } catch (NoSuchFileException | SlotRecord.InvalidRecordException e) {
            throw new VerificationException("Slot " + slot + " has no valid record: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new UnreadableException("Record of slot " + slot + " cannot be read now: " + e.getMessage(), e);
        }
        verifyRecord(record, "Slot " + slot, floor);
        if (!Files.isRegularFile(store.jar(slot))) {
            throw new VerificationException("Slot " + slot + " has no jar");
        }
        try {
            String actual = SlotStore.sha256(store.jar(slot));
            if (!actual.equals(record.sha256())) {
                throw new VerificationException("Jar of slot " + slot + " does not match its signed record");
            }
        } catch (IOException e) {
            throw new UnreadableException("Jar of slot " + slot + " cannot be read now: " + e.getMessage(), e);
        }
        return record;
    }
}
