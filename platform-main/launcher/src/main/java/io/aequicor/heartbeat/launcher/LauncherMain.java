package io.aequicor.heartbeat.launcher;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/**
 * Точка входа лаунчера: первый запуск из seed (если нужен), выбор поезда, запуск приложения из загрузчика классов
 * поезда. Без поезда приложение стартует на classpath установщика (монолитная установка).
 *
 * <p>Настройка системными свойствами: {@code heartbeat.launcher.root} (хранилище поездов), {@code heartbeat.data.dir}
 * (данные приложения для бэкапа БД), {@code heartbeat.seed.dir} (seed установщика), {@code heartbeat.app.main}
 * (класс приложения с {@code main(String[])}).
 */
public final class LauncherMain {
    static final String ROOT = "heartbeat.launcher.root";
    static final String DATA_DIR = "heartbeat.data.dir";
    static final String SEED_DIR = "heartbeat.seed.dir";
    static final String APP_MAIN = "heartbeat.app.main";
    static final String DEFAULT_APP_MAIN = "io.aequicor.heartbeat.platform.desktop.MainKt";

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
        String seed = System.getProperty(SEED_DIR);
        if (seed != null) {
            ensureFirstLaunch(new FirstLaunch(launcher, Path.of(seed)));
        }
        Launcher.Prepared prepared = launcher.prepare();
        launcher.exposeToApp(prepared, root);
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (prepared.train() != null) {
            loader = Launcher.newClassLoader(prepared.classpath(), ClassLoader.getSystemClassLoader());
            Thread.currentThread().setContextClassLoader(loader);
        }
        scheduleStable(launcher);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                launcher.markCleanExit();
            } catch (IOException ignored) {
                // the next start counts it as a crash, which is the safe direction
            }
        }, "heartbeat-launcher-exit"));
        try {
            invokeMain(loader, System.getProperty(APP_MAIN, DEFAULT_APP_MAIN), args);
        } catch (InvocationTargetException e) {
            if (prepared.train() != null && launcher.reportBundleFailure() != null) {
                System.err.println("Bundle failure, train rolled back, restart required: " + e.getCause());
            }
            throw e;
        }
    }

    /** Показывает экран первого запуска (или работает без окна в headless) и ждёт активации поезда. */
    private static void ensureFirstLaunch(FirstLaunch firstLaunch) throws IOException, InterruptedException {
        if (!firstLaunch.isNeeded()) {
            return;
        }
        String baseUrl = firstLaunch.baseUrl();
        FirstLaunchScreen.Task task = listener -> {
            if (baseUrl == null) {
                throw new IOException("Seed has no baseUrl and bundles are missing");
            }
            firstLaunch.run(new HttpFetcher(baseUrl), listener);
        };
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
            } catch (IOException ignored) {
                // stability is retried at the next start
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
