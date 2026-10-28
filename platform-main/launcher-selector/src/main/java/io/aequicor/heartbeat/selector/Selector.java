package io.aequicor.heartbeat.selector;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Выбор слота лаунчера (A/B) и учёт пробных запусков.
 *
 * <p>Слот подтверждён ({@link SlotState#confirmed}), когда лаунчер дошёл до входа в приложение
 * ({@link #confirm()}). Новый слот ({@link #activateTrial}) пробный: запуск, который не дошёл до подтверждения
 * (процесс снят, исключение лаунчера), засчитывается в {@link SlotState#crashes} при следующем выборе. После
 * {@link #CRASH_LIMIT} таких запусков слот отвергается — его SHA-256 попадает в {@link SlotState#bad}, а выбирается
 * последний подтверждённый слот; без него — встроенный в установщик лаунчер. Подтверждённый слот не перезаписывается:
 * обновление всегда пишет в соседний.
 *
 * <p>Слот запускается и активируется только после проверки ({@link SlotVerifier}): подпись доверенного ключа, SHA-256
 * jar и уровень лаунчера не ниже {@link SlotState#floor}. Не прошедший проверку слот отвергается так же, как не
 * запустившийся, а встроенный лаунчер входит в подписанный установщик и не проверяется.
 *
 * <p>Состояние слотов меняет только владелец ({@link #becomeOwner()}): второй экземпляр и вспомогательные процессы
 * выбирают слот на чтение и ничего не считают и не пишут. Файл состояния новее поддерживаемого формата
 * ({@link SlotState.UnsupportedFormatException}) не читается и не затирается: запускается встроенный лаунчер, который
 * знает собственный формат.
 *
 * <p>Методы синхронизированы: состояние слотов пишут главный поток (выбор, подтверждение) и фоновый поток лаунчера
 * (предложение нового jar, {@link #stageUpdate}).
 */
final class Selector {
    /** Пробных запусков подряд без подтверждения до отката на последний подтверждённый слот. */
    static final int CRASH_LIMIT = 2;

    private final SlotStore store;
    private final SlotVerifier verifier;
    private boolean owner;

    Selector(Path slotsDir, TrustedKeys keys) {
        this.store = new SlotStore(slotsDir);
        this.verifier = new SlotVerifier(keys);
    }

    SlotStore store() {
        return store;
    }

    /** Берёт право менять состояние слотов; {@code false} — другой процесс уже владелец, работаем на чтение. */
    boolean becomeOwner() {
        owner = store.acquireOwnerLock();
        return owner;
    }

    /**
     * Выбирает лаунчер для запуска. Владелец сначала засчитывает неподтверждённый пробный запуск прошлого процесса,
     * отвергает слот, исчерпавший {@link #CRASH_LIMIT}, и помечает новый пробный запуск; не владелец только читает.
     *
     * @param excluded слоты, которые не удалось запустить в этом процессе: их не выбирают снова
     */
    synchronized Choice choose(Set<String> excluded) {
        try {
            return chooseInState(excluded);
        } catch (IOException | RuntimeException e) {
            // the selector must never keep the app from starting: without a readable and writable state it runs the
            // embedded launcher and changes nothing
            System.err.println("Slots are unavailable, the embedded launcher starts: " + e);
            return Choice.embedded();
        }
    }

    private Choice chooseInState(Set<String> excluded) throws IOException {
        SlotState state;
        try {
            state = store.state();
        } catch (SlotState.UnsupportedFormatException e) {
            System.err.println("Slots are not read, the embedded launcher starts: " + e.getMessage());
            return Choice.embedded();
        }
        if (!owner) {
            return pick(state, excluded);
        }
        if (state.launching) {
            state.crashes++;
            state.launching = false;
        }
        if (state.isTrial() && state.crashes >= CRASH_LIMIT) {
            System.err.println("Slot " + state.active + " did not start " + state.crashes + " times, rolling back to "
                + (state.confirmed.isEmpty() ? "the embedded launcher" : "slot " + state.confirmed));
            reject(state, state.active);
        }
        Choice choice = pick(state, excluded);
        state.launching = choice.trial();
        state.save(store.stateFile());
        return choice;
    }

    /**
     * Слот дошёл до входа в приложение: пробный становится подтверждённым, счётчик сбрасывается. Идемпотентно;
     * не владелец ничего не меняет.
     */
    synchronized void confirm() throws IOException {
        if (!owner) {
            return;
        }
        SlotState state = readForUpdate();
        if (state == null) {
            return;
        }
        if (state.isTrial()) {
            state.confirmed = state.active;
        }
        raiseFloor(state);
        state.crashes = 0;
        state.launching = false;
        state.save(store.stateFile());
    }

    /**
     * Делает слот пробным: он запустится следующим, подтверждённый останется последним надёжным. Слот должен быть
     * соседним подтверждённому (подтверждённый не перезаписывается), содержать jar и не быть отвергнутым.
     *
     * @return {@code false}, если слот не принят: нет jar, jar отвергнут раньше или не владелец
     */
    synchronized boolean activateTrial(String slot) throws IOException {
        if (!owner || !store.hasJar(slot)) {
            return false;
        }
        SlotState state = readForUpdate();
        if (state == null) {
            return false;
        }
        if (slot.equals(state.confirmed)) {
            throw new IllegalArgumentException("Slot " + slot + " is the confirmed one and is not replaced");
        }
        if (state.bad.contains(SlotStore.sha256(store.jar(slot)))) {
            return false;
        }
        try {
            verifier.verify(store, slot, state.floor);
        } catch (SlotVerifier.VerificationException e) {
            System.err.println("Slot " + slot + " is not activated: " + e.getMessage());
            return false;
        }
        state.active = slot;
        state.crashes = 0;
        state.launching = false;
        state.save(store.stateFile());
        return true;
    }

    /**
     * Слот не удалось запустить вовсе (jar не открывается, нет класса лаунчера): он отвергается сразу, без счёта
     * крахов. Подтверждённый слот в этом случае тоже перестаёт быть надёжным.
     */
    synchronized void abandon(String slot) throws IOException {
        if (!owner) {
            return;
        }
        SlotState state = readForUpdate();
        if (state == null) {
            return;
        }
        reject(state, slot);
        state.save(store.stateFile());
    }

    /**
     * Пробный слот не виноват в отказе (лаунчер отложил операцию с данными до подтверждённого лаунчера): слот
     * освобождается без отвержения и без счёта крахов, активным снова становится подтверждённый. Jar остаётся в слоте.
     */
    synchronized void release(String slot) throws IOException {
        if (!owner) {
            return;
        }
        SlotState state = readForUpdate();
        if (state == null) {
            return;
        }
        if (slot.equals(state.active) && !slot.equals(state.confirmed)) {
            state.active = state.confirmed;
        }
        state.crashes = 0;
        state.launching = false;
        state.save(store.stateFile());
    }

    /**
     * Лаунчер предлагает jar нового лаунчера из поезда (запрос — строки {@code SlotUpdate} лаунчера). Принятый jar
     * копируется в соседний слот и становится пробным: запустится при следующем старте. Принимается только когда:
     * запись подписана доверенным ключом и её уровень не ниже порога, jar не отвергался раньше и не совпадает с уже
     * подтверждённым, идёт не пробный запуск (оба слота заняты: работающий и подтверждённый), а запись jar в слот
     * удалась. Копия идёт во временный файл с проверкой SHA-256 и атомарным переименованием; пока запись не завершена,
     * у слота нет записи, и он недействителен. Занятый (Windows) или недописанный слот откладывает обновление.
     *
     * @return {@code true}, если новый слот активирован как пробный
     */
    synchronized boolean stageUpdate(Map<String, String> request) throws IOException {
        if (!owner) {
            return false;
        }
        SlotRecord record;
        Path source;
        try {
            record = new SlotRecord(request.get("id"), request.get("version"), request.get("sha256"),
                request.get("kind"), Integer.parseInt(request.get("launcherApi")), request.get("signature"));
            source = Path.of(request.get("jar"));
        } catch (IllegalArgumentException | NullPointerException e) {
            System.err.println("Launcher update request is invalid: " + e);
            return false;
        }
        SlotState state = readForUpdate();
        if (state == null) {
            return false;
        }
        try {
            verifier.verifyRecord(record, "Launcher " + record.version(), state.floor);
        } catch (SlotVerifier.VerificationException e) {
            System.err.println("Launcher is not staged: " + e.getMessage());
            return false;
        }
        if (state.bad.contains(record.sha256()) || state.isTrial() || state.launching
            || record.sha256().equals(sha256Of(state.confirmed))) {
            return false;
        }
        String target = state.confirmed.isEmpty() ? SlotStore.SLOTS.get(0) : SlotStore.other(state.confirmed);
        try {
            install(source, target, record);
        } catch (IOException e) {
            System.err.println("Launcher is not staged, the slot " + target + " is busy or unwritable: " + e);
            return false;
        }
        return activateTrial(target);
    }

    /** Кладёт jar и запись в {@code slot}: сначала запись удаляется (слот недействителен), последней пишется новая. */
    private void install(Path source, String slot, SlotRecord record) throws IOException {
        Path dir = store.slotDir(slot);
        Files.createDirectories(dir);
        Path tmp = dir.resolve(SlotStore.JAR_NAME + ".tmp");
        try {
            Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING);
            if (!SlotStore.sha256(tmp).equals(record.sha256())) {
                throw new IOException("Jar " + source + " does not match its record");
            }
            Files.deleteIfExists(dir.resolve(SlotRecord.FILE_NAME));
            Files.move(tmp, store.jar(slot), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
        record.write(dir.resolve(SlotRecord.FILE_NAME));
    }

    /** SHA-256 jar слота по его записи; у пустого или нечитаемого слота — пустая строка. */
    private String sha256Of(String slot) {
        if (slot.isEmpty()) {
            return "";
        }
        try {
            return SlotRecord.read(store.slotDir(slot).resolve(SlotRecord.FILE_NAME)).sha256();
        } catch (IOException e) {
            return "";
        }
    }

    private SlotState readForUpdate() throws IOException {
        try {
            return store.state();
        } catch (SlotState.UnsupportedFormatException e) {
            System.err.println("Slots are not changed: " + e.getMessage());
            return null;
        }
    }

    /** Отвергает {@code slot}: его jar запоминается как плохой, активным становится подтверждённый слот. */
    private void reject(SlotState state, String slot) {
        if (store.hasJar(slot)) {
            try {
                state.bad.add(SlotStore.sha256(store.jar(slot)));
            } catch (IOException e) {
                System.err.println("The jar of slot " + slot + " is rejected, but its hash is not remembered: " + e);
            }
        }
        if (slot.equals(state.confirmed)) {
            state.confirmed = "";
        }
        if (slot.equals(state.active)) {
            state.active = state.confirmed;
        }
        state.crashes = 0;
        state.launching = false;
    }

    /**
     * Лаунчер для запуска по состоянию: активный слот, иначе подтверждённый, иначе встроенный. Слот должен пройти
     * проверку; не прошедший владелец отвергает (его jar запоминается), не владелец только пропускает.
     */
    private Choice pick(SlotState state, Set<String> excluded) {
        boolean skipped = false;
        for (String slot : new LinkedHashSet<>(List.of(state.active, state.confirmed))) {
            if (!usable(slot, excluded)) {
                continue;
            }
            try {
                verifier.verify(store, slot, state.floor);
            } catch (SlotVerifier.UnreadableException e) {
                // temporary: the slot is skipped for this start and stays as it was
                System.err.println("Slot " + slot + " is skipped: " + e.getMessage());
                skipped = true;
                continue;
            } catch (SlotVerifier.VerificationException e) {
                System.err.println("Slot " + slot + " is not started: " + e.getMessage());
                if (owner) {
                    reject(state, slot);
                }
                continue;
            }
            boolean trial = !slot.equals(state.confirmed);
            if (owner && !skipped && !slot.equals(state.active)) {
                state.active = slot;
                state.crashes = 0;
            }
            return Choice.slot(slot, store.jar(slot), trial);
        }
        if (owner && !skipped) {
            state.active = "";
            state.crashes = 0;
        }
        return Choice.embedded();
    }

    /** Порог поднимается до уровня подтверждённого слота; запись, которую не прочитать, порог не меняет. */
    private void raiseFloor(SlotState state) {
        if (state.confirmed.isEmpty()) {
            return;
        }
        try {
            SlotRecord record = SlotRecord.read(store.slotDir(state.confirmed).resolve(SlotRecord.FILE_NAME));
            state.floor = Math.max(state.floor, record.launcherApi());
        } catch (IOException e) {
            System.err.println("Level of slot " + state.confirmed + " is not recorded: " + e.getMessage());
        }
    }

    private boolean usable(String slot, Set<String> excluded) {
        return !slot.isEmpty() && !excluded.contains(slot) && store.hasJar(slot);
    }
}
