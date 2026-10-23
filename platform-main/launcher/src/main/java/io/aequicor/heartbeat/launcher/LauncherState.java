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
 * Записывается атомарно (временный файл + move).
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
    final Set<String> bad = new LinkedHashSet<>();

    static LauncherState load(Path file) throws IOException {
        LauncherState state = new LauncherState();
        if (!Files.exists(file)) {
            return state;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        }
        state.current = props.getProperty("current", "");
        state.previous = props.getProperty("previous", "");
        state.crashes = Integer.parseInt(props.getProperty("crashes", "0"));
        state.running = Boolean.parseBoolean(props.getProperty("running", "false"));
        state.stable = Boolean.parseBoolean(props.getProperty("stable", "true"));
        state.dbSchema = Integer.parseInt(props.getProperty("dbSchema", "0"));
        state.backupOf = props.getProperty("backupOf", "");
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
        props.setProperty("bad", String.join(",", bad));
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            props.store(out, null);
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
