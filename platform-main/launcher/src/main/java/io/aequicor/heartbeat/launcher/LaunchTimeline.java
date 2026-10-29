package io.aequicor.heartbeat.launcher;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Протокол измерения времени запуска: метки этапов одного запуска в JSONL-файле. Каждая метка — одна строка
 * {@code {"launch":"<pid>@<старт JVM>","stage":"<этап>","t":<мс от старта JVM>,"wall":<эпоха, мс>}}.
 * Файл — {@code <каталог данных>/logs/launch-timeline-<launch>.jsonl}; каталог данных определяется так же, как
 * в {@link LauncherMain} (свойство {@code heartbeat.data.dir}, при {@code heartbeat.launcher.default.root=true} —
 * каталог пользователя ОС). Файлы старше пяти суток удаляются при первой метке процесса. Компоненты одного запуска
 * (селектор, лаунчер слота, приложение) пишут тот же формат из своих копий протокола и собираются в один файл
 * по общему {@code launch}; JVM-wide статика для этого не нужна: класс лаунчера живёт в изолированном загрузчике слота.
 *
 * <p>Измерение не должно влиять на запуск: сбой записи отключает протокол одним сообщением в {@code System.err}
 * (в этом модуле логирования ещё нет) и не бросается вызывателю. Отключение: {@code -Dheartbeat.launch.timeline=off},
 * каталог для проверок: {@code -Dheartbeat.launch.timeline.dir=<каталог>}.
 */
public final class LaunchTimeline {
    /** Свойство выключения протокола; значение {@code off} отключает записи. */
    static final String TIMELINE_PROPERTY = "heartbeat.launch.timeline";
    /** Свойство с явным каталогом данных для проверок; в проде не задаётся. */
    static final String DIR_PROPERTY = "heartbeat.launch.timeline.dir";
    static final long RETENTION_MILLIS = Duration.ofDays(5).toMillis();

    private static final AtomicBoolean broken = new AtomicBoolean();

    private LaunchTimeline() {
    }

    /** Записывает метку этапа; при выключенном или сломанном протоколе ничего не делает. */
    public static void mark(String stage) {
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
            long start = ManagementFactory.getRuntimeMXBean().getStartTime();
            String line = "{\"launch\":\"" + launch + "\",\"stage\":\"" + escape(stage) + "\",\"t\":"
                + (System.currentTimeMillis() - start) + ",\"wall\":" + System.currentTimeMillis() + "}";
            Files.writeString(file, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            report(e);
        }
    }

    private static boolean enabled() {
        return !"off".equalsIgnoreCase(System.getProperty(TIMELINE_PROPERTY, "on"));
    }

    /** Каталог данных по тем же правилам, что {@link LauncherMain}: свойство, каталог пользователя ОС, временный. */
    static Path resolveDir() {
        String explicit = System.getProperty(DIR_PROPERTY);
        if (explicit != null && !explicit.isBlank()) {
            return Path.of(explicit);
        }
        String dataDir = System.getProperty(LauncherMain.DATA_DIR);
        if (dataDir != null && !dataDir.isBlank()) {
            return Path.of(dataDir);
        }
        if (Boolean.getBoolean(LauncherMain.DEFAULT_ROOT)) {
            return LauncherMain.userDataDir(System.getProperty("os.name", ""), System.getProperty("user.home", ""),
                System.getenv("APPDATA"), System.getenv("XDG_DATA_HOME"), System.getProperty(LauncherMain.DATA_NAME));
        }
        return Path.of(System.getProperty("java.io.tmpdir")).resolve("heartbeat-launch-timeline");
    }

    /** Идентификатор запуска: общий для всех компонентов одной JVM, отличный для воркеров. */
    static String launchId() {
        return ProcessHandle.current().pid() + "@" + ManagementFactory.getRuntimeMXBean().getStartTime();
    }

    /** Убирает файлы протокола старше удержания; сбой не отключает протокол, но сообщается один раз. */
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

    /** Экранирует строку JSON без зависимостей: модуль должен оставаться самодостаточным. */
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

    /** Первый сбой протокола — последнее его сообщение: измерение не должно флудить и влиять на запуск. */
    private static void report(Exception e) {
        if (broken.compareAndSet(false, true)) {
            System.err.println("Launch timeline is off after a write failure: " + e);
        }
    }
}
