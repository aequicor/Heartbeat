package io.aequicor.heartbeat.selector;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Метки времени запуска селектора: собственная копия протокола лаунчера ({@code LaunchTimeline}), потому что селектор
 * запускается раньше лаунчера и не может зависеть от его модулей (см. {@link SelectorPaths}). Формат общий: каждая
 * метка — строка {@code {"launch":"<pid>@<старт JVM>","stage":"<этап>","t":<мс от старта JVM>,"wall":<эпоха, мс>}}
 * в {@code <каталог данных>/logs/launch-timeline-<launch>.jsonl}; компоненты одной JVM собираются в один файл по
 * {@code launch}. Отключение: {@code -Dheartbeat.launch.timeline=off}, каталог для проверок:
 * {@code -Dheartbeat.launch.timeline.dir=<каталог>}. Удержание файлов — пять суток. Сбой записи отключает протокол
 * одним сообщением и не влияет на запуск.
 */
final class LaunchMarks {
    static final String TIMELINE_PROPERTY = "heartbeat.launch.timeline";
    static final String DIR_PROPERTY = "heartbeat.launch.timeline.dir";
    static final long RETENTION_MILLIS = Duration.ofDays(5).toMillis();

    private static final AtomicBoolean broken = new AtomicBoolean();

    private LaunchMarks() {
    }

    /** Записывает метку этапа; при выключенном или сломанном протоколе ничего не делает. */
    static void mark(String stage) {
        if (broken.get() || !enabled()) {
            return;
        }
        try {
            Path dir = resolveDir().resolve("logs");
            Files.createDirectories(dir);
            String launch = launchId();
            Path file = dir.resolve("launch-timeline-" + launch + ".jsonl");
            if (!Files.exists(file)) {
                prune(dir);
            }
            long start = java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime();
            String line = "{\"launch\":\"" + launch + "\",\"stage\":\"" + escape(stage) + "\",\"t\":"
                + (System.currentTimeMillis() - start) + ",\"wall\":" + System.currentTimeMillis() + "}";
            Files.writeString(file, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            if (broken.compareAndSet(false, true)) {
                System.err.println("Launch timeline is off after a write failure: " + e);
            }
        }
    }

    private static boolean enabled() {
        return !"off".equalsIgnoreCase(System.getProperty(TIMELINE_PROPERTY, "on"));
    }

    /** Каталог данных по тем же правилам, что {@link SelectorPaths}: свойство, каталог пользователя ОС, временный. */
    static Path resolveDir() {
        String explicit = System.getProperty(DIR_PROPERTY);
        if (explicit != null && !explicit.isBlank()) {
            return Path.of(explicit);
        }
        String dataDir = System.getProperty(SelectorPaths.DATA_DIR);
        if (dataDir != null && !dataDir.isBlank()) {
            return Path.of(dataDir);
        }
        if (Boolean.getBoolean(SelectorPaths.DEFAULT_ROOT)) {
            return SelectorPaths.userDataDir(System.getProperty("os.name", ""), System.getProperty("user.home", ""),
                System.getenv("APPDATA"), System.getenv("XDG_DATA_HOME"), System.getProperty(SelectorPaths.DATA_NAME));
        }
        return Path.of(System.getProperty("java.io.tmpdir")).resolve("heartbeat-launch-timeline");
    }

    static String launchId() {
        return ProcessHandle.current().pid() + "@"
            + java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime();
    }

    private static void prune(Path dir) {
        try (var stream = Files.list(dir)) {
            long now = System.currentTimeMillis();
            stream.filter(path -> path.getFileName().toString().startsWith("launch-timeline-")
                    && path.getFileName().toString().endsWith(".jsonl"))
                .forEach(path -> {
                    try {
                        if (Files.getLastModifiedTime(path).toMillis() < now - RETENTION_MILLIS) {
                            Files.delete(path);
                        }
                    } catch (IOException | RuntimeException e) {
                        report(e);
                    }
                });
        } catch (IOException | RuntimeException e) {
            report(e);
        }
    }

    static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    private static void report(Exception e) {
        if (broken.compareAndSet(false, true)) {
            System.err.println("Launch timeline is off after a write failure: " + e);
        }
    }
}
