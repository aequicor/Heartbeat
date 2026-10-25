package io.aequicor.heartbeat.launcher;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Точка входа лаунчера: первый запуск из seed (если нужен), выбор поезда, запуск приложения из загрузчика классов
 * поезда. Без поезда приложение стартует на classpath установщика (монолитная установка).
 *
 * <p>Настройка системными свойствами: {@code heartbeat.launcher.root} (хранилище поездов), {@code heartbeat.data.dir}
 * (данные приложения для бэкапа БД), {@code heartbeat.seed.dir} (seed установщика), {@code heartbeat.app.main}
 * (класс приложения с {@code main(String[])}).
 *
 * <p>Состояние лаунчера меняет только владелец {@code instance.lock} в корне: он держит блокировку всю жизнь процесса.
 * Второй экземпляр и вспомогательные процессы того же исполняемого файла (первый аргумент начинается с {@code --}:
 * воркеры {@code --heartbeat-*}, проверки упаковки) блокировку не берут и работают только на чтение
 * ({@link Launcher#prepareReadOnly()}): без первого запуска, учёта крахов, активации ожидающего поезда, отката,
 * таймера стабильности и отметки выхода.
 *
 * <p>Штатным считается выход, когда {@code main} приложения вернулся без исключения, либо когда приложение перед
 * выходом выставило системное свойство {@value #EXIT_CLEAN}{@code =true} (выход через {@code exitProcess} после
 * закрытия окон). Остальные выходы ({@code exit(≠0)}, SIGTERM, исключение) до окна стабильности засчитываются крахом
 * свежего поезда. Исключение из {@code main} до окна стабильности запрашивает откат поезда
 * ({@link Launcher#reportBundleFailure()}).
 */
public final class LauncherMain {
    static final String ROOT = "heartbeat.launcher.root";
    static final String DATA_DIR = "heartbeat.data.dir";
    static final String SEED_DIR = "heartbeat.seed.dir";
    static final String APP_MAIN = "heartbeat.app.main";
    static final String EXIT_CLEAN = "heartbeat.exit.clean";
    static final String DEFAULT_APP_MAIN = "io.aequicor.heartbeat.platform.desktop.MainKt";

    /** Блокировка экземпляра; держится до конца процесса (поле не даёт сборщику освободить её). */
    private static FileChannel instanceLock;

    private LauncherMain() {
    }

    public static void main(String[] args) throws Exception {
        String rootValue = System.getProperty(ROOT);
        if (rootValue == null) {
            throw new IllegalStateException("System property " + ROOT + " is required");
        }
        Path root = Path.of(rootValue);
        Path dataDir = Path.of(System.getProperty(DATA_DIR, root.resolve("data").toString()));
        Launcher launcher = new Launcher(root, dataDir);
        boolean owner = false;
        if (!isAuxiliary(args)) {
            instanceLock = acquireInstanceLock(root);
            owner = instanceLock != null;
        }
        String seed = System.getProperty(SEED_DIR);
        if (owner && seed != null) {
            ensureFirstLaunch(new FirstLaunch(launcher, Path.of(seed)));
        }
        Launcher.Prepared prepared = owner ? launcher.prepare() : launcher.prepareReadOnly();
        launcher.exposeToApp(prepared, root);
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (prepared.train() != null) {
            loader = Launcher.newClassLoader(prepared.classpath(), ClassLoader.getSystemClassLoader());
            Thread.currentThread().setContextClassLoader(loader);
        }
        AtomicBoolean returned = new AtomicBoolean();
        if (owner) {
            scheduleStable(launcher);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (!isCleanExit(returned.get())) {
                    return;
                }
                try {
                    launcher.markCleanExit();
                } catch (IOException e) {
                    // the next start counts it as a crash, which is the safe direction
                    System.err.println("Clean exit was not recorded: " + e);
                }
            }, "heartbeat-launcher-exit"));
        }
        try {
            invokeMain(loader, System.getProperty(APP_MAIN, DEFAULT_APP_MAIN), args);
            returned.set(true);
        } catch (InvocationTargetException e) {
            if (owner && prepared.train() != null) {
                String target = launcher.reportBundleFailure();
                if (target != null) {
                    System.err.println("Bundle failure, train rolls back to " + target + " at the next start: "
                        + e.getCause());
                }
            }
            throw e;
        }
    }

    /** Вспомогательный процесс того же исполняемого файла (воркер, проверка): первый аргумент — флаг {@code --…}. */
    static boolean isAuxiliary(String[] args) {
        return args.length > 0 && args[0].startsWith("--");
    }

    /** Выход штатный: {@code main} вернулся или приложение выставило {@value #EXIT_CLEAN}. */
    static boolean isCleanExit(boolean mainReturned) {
        return mainReturned || Boolean.getBoolean(EXIT_CLEAN);
    }

    /**
     * Берёт блокировку экземпляра {@code instance.lock} в корне без ожидания.
     *
     * @return открытый канал, держащий блокировку, либо {@code null}, если её держит другой процесс (или этот же)
     */
    static FileChannel acquireInstanceLock(Path root) throws IOException {
        Files.createDirectories(root);
        FileChannel channel = FileChannel.open(root.resolve("instance.lock"),
            StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            if (channel.tryLock() != null) {
                return channel;
            }
        } catch (OverlappingFileLockException e) {
            // этот процесс уже держит блокировку: второй владелец не нужен
        }
        channel.close();
        return null;
    }

    /** Показывает экран первого запуска (или работает без окна в headless) и ждёт активации поезда. */
    private static void ensureFirstLaunch(FirstLaunch firstLaunch) throws IOException, InterruptedException {
        if (!firstLaunch.isNeeded()) {
            return;
        }
        // Без baseUrl (офлайн-установщик) качаются только бандлы со своим адресом; недостающий jar — ошибка.
        String baseUrl = firstLaunch.baseUrl();
        FirstLaunchScreen.Task task = listener -> firstLaunch.run(new HttpFetcher(baseUrl), listener);
        if (GraphicsEnvironment.isHeadless()) {
            task.run(new FirstLaunch.Listener() {
                @Override
                public void onLog(String line) {
                    System.err.println(line);
                }

                @Override
                public void onProgress(int done, int total, String bundleId, long bytes) {
                }
            });
            return;
        }
        CountDownLatch ready = new CountDownLatch(1);
        javax.swing.SwingUtilities.invokeLater(() -> new FirstLaunchScreen(task, ready::countDown).show());
        ready.await();
    }

    private static void scheduleStable(Launcher launcher) {
        Thread timer = new Thread(() -> {
            try {
                Thread.sleep(Launcher.STABLE_AFTER_MILLIS);
                launcher.markStable();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                // stability is retried at the next start
                System.err.println("Stability was not recorded: " + e);
            }
        }, "heartbeat-launcher-stable");
        timer.setDaemon(true);
        timer.start();
    }

    static void invokeMain(ClassLoader loader, String className, String[] args) throws Exception {
        Method main = Class.forName(className, true, loader).getMethod("main", String[].class);
        main.invoke(null, (Object) args);
    }
}
