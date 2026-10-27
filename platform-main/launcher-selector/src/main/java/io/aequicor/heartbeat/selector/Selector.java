package io.aequicor.heartbeat.selector;

import java.io.IOException;
import java.nio.file.Path;
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
 * <p>Состояние слотов меняет только владелец ({@link #becomeOwner()}): второй экземпляр и вспомогательные процессы
 * выбирают слот на чтение и ничего не считают и не пишут. Файл состояния новее поддерживаемого формата
 * ({@link SlotState.UnsupportedFormatException}) не читается и не затирается: запускается встроенный лаунчер, который
 * знает собственный формат.
 */
final class Selector {
    /** Пробных запусков подряд без подтверждения до отката на последний подтверждённый слот. */
    static final int CRASH_LIMIT = 2;

    private final SlotStore store;
    private boolean owner;

    Selector(Path slotsDir) {
        this.store = new SlotStore(slotsDir);
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
    Choice choose(Set<String> excluded) throws IOException {
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
    void confirm() throws IOException {
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
    boolean activateTrial(String slot) throws IOException {
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
    void abandon(String slot) throws IOException {
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

    private SlotState readForUpdate() throws IOException {
        try {
            return store.state();
        } catch (SlotState.UnsupportedFormatException e) {
            System.err.println("Slots are not changed: " + e.getMessage());
            return null;
        }
    }

    /** Отвергает {@code slot}: его jar запоминается как плохой, активным становится подтверждённый слот. */
    private void reject(SlotState state, String slot) throws IOException {
        if (store.hasJar(slot)) {
            state.bad.add(SlotStore.sha256(store.jar(slot)));
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

    /** Лаунчер для запуска по состоянию: активный слот, иначе подтверждённый, иначе встроенный. */
    private Choice pick(SlotState state, Set<String> excluded) {
        if (usable(state.active, excluded)) {
            return Choice.slot(state.active, store.jar(state.active), state.isTrial());
        }
        if (usable(state.confirmed, excluded)) {
            if (owner) {
                state.active = state.confirmed;
                state.crashes = 0;
            }
            return Choice.slot(state.confirmed, store.jar(state.confirmed), false);
        }
        if (owner) {
            state.active = "";
            state.crashes = 0;
        }
        return Choice.embedded();
    }

    private boolean usable(String slot, Set<String> excluded) {
        return !slot.isEmpty() && !excluded.contains(slot) && store.hasJar(slot);
    }
}
