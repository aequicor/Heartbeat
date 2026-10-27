package io.aequicor.heartbeat.selector;

import java.nio.file.Path;

/**
 * Выбранный для запуска лаунчер: слот ({@link Kind#SLOT}) или встроенный в установщик ({@link Kind#EMBEDDED}).
 *
 * @param slot имя слота ({@code "A"} / {@code "B"}), у встроенного — {@code ""}
 * @param jar jar слота; у встроенного {@code null}: путь к нему знает только {@link SelectorMain}
 * @param trial слот пробный и после запуска ждёт подтверждения
 */
record Choice(Kind kind, String slot, Path jar, boolean trial) {
    enum Kind { SLOT, EMBEDDED }

    static Choice embedded() {
        return new Choice(Kind.EMBEDDED, "", null, false);
    }

    static Choice slot(String slot, Path jar, boolean trial) {
        return new Choice(Kind.SLOT, slot, jar, trial);
    }
}
