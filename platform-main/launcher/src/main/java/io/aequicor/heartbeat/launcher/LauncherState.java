package io.aequicor.heartbeat.launcher;

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
 * Локальное состояние лаунчера (файл {@code state.properties}): текущий и предыдущий поезд, плохие поезда,
 * счётчик крахов свежего поезда и признаки «процесс запущен» / «прожил окно стабильности».
 * Записывается атомарно (временный файл + move); изменения (чтение → правка → запись) идут под
 * {@link TrainStore#locked}. Повреждённый файл не роняет запуск: нечитаемые числа берутся по умолчанию, а нечитаемый
 * файл целиком — как пустое состояние.
 *
 * <p>Файл несёт версию формата ({@code format}): состояние пишут разные версии лаунчера по очереди (слоты, откат
 * лаунчера), поэтому лаунчер, который не знает формат, не должен ни читать его как пустое состояние, ни перезаписывать.
 * Формат без поля — первый ({@value #FORMAT}). Файл новее поддерживаемого формата не читается
 * ({@link UnsupportedFormatException}), а неизвестные поля поддерживаемого формата сохраняются при записи.
 */
final class LauncherState {
    /** Версия формата, которую читает и пишет этот лаунчер. */
    static final int FORMAT = 1;

    private static final Set<String> KNOWN = Set.of("format", "current", "previous", "crashes", "running", "stable",
        "dbSchema", "backupOf", "backupSchema", "rollbackRequested", "bad");

    /** Файл состояния записан более новым лаунчером: этот его не читает и не затирает. */
    static final class UnsupportedFormatException extends IOException {
        UnsupportedFormatException(Path file, int found) {
            super("Launcher state " + file + " has format " + found + ", supported " + FORMAT);
        }
    }

    /** Поля файла, которых этот лаунчер не знает: пишутся обратно без изменений. */
    final Map<String, String> unknown = new TreeMap<>();
    String current = "";
    String previous = "";
    int crashes;
    boolean running;
    boolean stable = true;
    /** Версия схем БД, под которую работал текущий поезд. */
    int dbSchema;
    /** Поезд, перед активацией которого сделан бэкап БД ({@code ""} — бэкапа нет). */
    String backupOf = "";
    /** Версия схем БД в бэкапе {@link #backupOf} ({@code -1} — неизвестна: состояние старого лаунчера или бэкапа нет). */
    int backupSchema = -1;
    /** Бандл текущего поезда не поднялся: следующий {@link Launcher#prepare()} откатывает поезд. */
    boolean rollbackRequested;
    final Set<String> bad = new LinkedHashSet<>();

    static LauncherState load(Path file) throws IOException {
        LauncherState state = new LauncherState();
        if (!Files.exists(file)) {
            return state;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IllegalArgumentException e) {
            System.err.println("Launcher state is corrupted, starting from an empty state: " + e);
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
        state.current = props.getProperty("current", "");
        state.previous = props.getProperty("previous", "");
        state.crashes = intOf(props, "crashes");
        state.running = Boolean.parseBoolean(props.getProperty("running", "false"));
        state.stable = Boolean.parseBoolean(props.getProperty("stable", "true"));
        state.dbSchema = intOf(props, "dbSchema");
        state.backupOf = props.getProperty("backupOf", "");
        state.backupSchema = props.containsKey("backupSchema") ? intOf(props, "backupSchema") : -1;
        state.rollbackRequested = Boolean.parseBoolean(props.getProperty("rollbackRequested", "false"));
        String badList = props.getProperty("bad", "");
        for (String item : badList.split(",")) {
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
        props.setProperty("current", current);
        props.setProperty("previous", previous);
        props.setProperty("crashes", Integer.toString(crashes));
        props.setProperty("running", Boolean.toString(running));
        props.setProperty("stable", Boolean.toString(stable));
        props.setProperty("dbSchema", Integer.toString(dbSchema));
        props.setProperty("backupOf", backupOf);
        if (backupSchema >= 0) {
            props.setProperty("backupSchema", Integer.toString(backupSchema));
        }
        props.setProperty("rollbackRequested", Boolean.toString(rollbackRequested));
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

    private static int intOf(Properties props, String key) {
        try {
            return Math.max(0, Integer.parseInt(props.getProperty(key, "0").trim()));
        } catch (NumberFormatException e) {
            System.err.println("Launcher state field " + key + " is corrupted, using 0: " + e.getMessage());
            return 0;
        }
    }
}
