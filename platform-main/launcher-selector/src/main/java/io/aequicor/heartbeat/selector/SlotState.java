package io.aequicor.heartbeat.selector;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

/**
 * Состояние слотов лаунчера (файл {@code slots/state.properties}): какой слот запускается ({@link #active}), какой
 * из них последний подтверждённый ({@link #confirmed}), идёт ли пробный запуск ({@link #launching}) и сколько пробных
 * запусков подряд не дошли до подтверждения ({@link #crashes}). Слот пробный, пока {@link #active} отличается от
 * {@link #confirmed}.
 *
 * <p>Файл несёт версию формата ({@code format}), как состояние лаунчера: селектор не меняется после установки, а
 * формат со временем растёт. Файл без поля — первый формат; файл новее {@value #FORMAT} или с нечитаемым значением не
 * читается ({@link UnsupportedFormatException}) и не перезаписывается, неизвестные поля поддерживаемого формата
 * сохраняются. Запись атомарна (временный файл + move); пишет только владелец слотов ({@link Selector#becomeOwner()}).
 */
final class SlotState {
    /** Версия формата, которую читает и пишет этот селектор. */
    static final int FORMAT = 1;

    private static final Set<String> KNOWN = Set.of("format", "active", "confirmed", "launching", "crashes", "bad");

    /** Файл состояния записан более новым селектором: этот его не читает и не затирает. */
    static final class UnsupportedFormatException extends IOException {
        UnsupportedFormatException(Path file, int found) {
            super("Slot state " + file + " has format " + found + ", supported " + FORMAT);
        }
    }

    /** Слот, который запускается следующим ({@code ""} — слотов нет, работает встроенный лаунчер). */
    String active = "";
    /** Последний слот, дошедший до подтверждения ({@code ""} — такого нет). */
    String confirmed = "";
    /** Пробный запуск начат и ещё не подтверждён. */
    boolean launching;
    /** Пробные запуски подряд, не дошедшие до подтверждения. */
    int crashes;
    /** SHA-256 отвергнутых jar: обновление не кладёт их в слот повторно. */
    final Set<String> bad = new LinkedHashSet<>();
    /** Поля файла, которых этот селектор не знает: пишутся обратно без изменений. */
    final Map<String, String> unknown = new TreeMap<>();

    /** Слот пробный: запускается, но ещё не подтверждён. */
    boolean isTrial() {
        return !active.isEmpty() && !active.equals(confirmed);
    }

    static SlotState load(Path file) throws IOException {
        SlotState state = new SlotState();
        if (!Files.exists(file)) {
            return state;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IllegalArgumentException e) {
            System.err.println("Slot state is corrupted, starting from an empty state: " + e);
            return state;
        }
        int format = formatOf(props);
        if (format < 1 || format > FORMAT) {
            throw new UnsupportedFormatException(file, format);
        }
        for (String key : props.stringPropertyNames()) {
            if (!KNOWN.contains(key)) {
                state.unknown.put(key, props.getProperty(key));
            }
        }
        state.active = slotOf(props.getProperty("active", ""));
        state.confirmed = slotOf(props.getProperty("confirmed", ""));
        state.launching = Boolean.parseBoolean(props.getProperty("launching", "false"));
        state.crashes = crashesOf(props.getProperty("crashes", "0"));
        for (String item : props.getProperty("bad", "").split(",")) {
            if (!item.isEmpty()) {
                state.bad.add(item);
            }
        }
        return state;
    }

    void save(Path file) throws IOException {
        Properties props = new Properties();
        props.putAll(unknown);
        props.setProperty("format", Integer.toString(FORMAT));
        props.setProperty("active", active);
        props.setProperty("confirmed", confirmed);
        props.setProperty("launching", Boolean.toString(launching));
        props.setProperty("crashes", Integer.toString(crashes));
        props.setProperty("bad", String.join(",", bad));
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            props.store(out, null);
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Формат файла: без поля — первый; нечитаемое значение — {@code -1} (такой файл не читается). */
    private static int formatOf(Properties props) {
        String value = props.getProperty("format");
        if (value == null) {
            return 1;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Имя слота из файла; всё, кроме известных слотов, — «слота нет». */
    private static String slotOf(String value) {
        String slot = value.trim();
        return SlotStore.SLOTS.contains(slot) ? slot : "";
    }

    private static int crashesOf(String value) {
        try {
            return Math.max(0, Integer.parseInt(value.trim()));
        } catch (NumberFormatException e) {
            System.err.println("Slot state field crashes is corrupted, using 0: " + e.getMessage());
            return 0;
        }
    }
}
