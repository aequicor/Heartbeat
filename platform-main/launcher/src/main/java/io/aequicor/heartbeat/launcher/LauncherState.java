package io.aequicor.heartbeat.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;

/**
 * Локальное состояние лаунчера (файл {@code state.properties}): текущий и предыдущий поезд, плохие поезда,
 * счётчик крахов свежего поезда и признаки «процесс запущен» / «прожил окно стабильности».
 * Записывается атомарно (временный файл + move); изменения (чтение → правка → запись) идут под
 * {@link TrainStore#locked}. Повреждённый файл не роняет запуск: нечитаемые числа берутся по умолчанию, а нечитаемый
 * файл целиком — как пустое состояние.
 */
final class LauncherState {
    String current = "";
    String previous = "";
    int crashes;
    boolean running;
    boolean stable = true;
    /** Версия схем БД, под которую работал текущий поезд. */
    int dbSchema;
    /** Поезд, перед активацией которого сделан бэкап БД ({@code ""} — бэкапа нет). */
    String backupOf = "";
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
        state.current = props.getProperty("current", "");
        state.previous = props.getProperty("previous", "");
        state.crashes = intOf(props, "crashes");
        state.running = Boolean.parseBoolean(props.getProperty("running", "false"));
        state.stable = Boolean.parseBoolean(props.getProperty("stable", "true"));
        state.dbSchema = intOf(props, "dbSchema");
        state.backupOf = props.getProperty("backupOf", "");
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
        props.setProperty("current", current);
        props.setProperty("previous", previous);
        props.setProperty("crashes", Integer.toString(crashes));
        props.setProperty("running", Boolean.toString(running));
        props.setProperty("stable", Boolean.toString(stable));
        props.setProperty("dbSchema", Integer.toString(dbSchema));
        props.setProperty("backupOf", backupOf);
        props.setProperty("rollbackRequested", Boolean.toString(rollbackRequested));
        props.setProperty("bad", String.join(",", bad));
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            props.store(out, null);
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
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
