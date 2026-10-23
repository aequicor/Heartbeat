package io.aequicor.heartbeat.launcher;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Лаунчер платформы (чистая JVM): строит classpath текущего поезда, считает краши свежего поезда,
 * откатывает поезд целиком и восстанавливает бэкап БД.
 *
 * <p>Жизненный цикл процесса: {@link #prepare()} при старте → {@link #markStable()} через
 * {@link #STABLE_AFTER_MILLIS} жизни → {@link #markCleanExit()} при выходе. Процесс, снятый без
 * {@code markCleanExit} до {@code markStable}, считается крахом свежего поезда; {@link #CRASH_LIMIT} краха подряд —
 * откат. Исключение при инициализации бандла ({@link #reportBundleFailure}) откатывает сразу.
 */
public final class Launcher {
    /** Версия API платформы, которую поддерживает этот лаунчер. */
    public static final int PLATFORM_API = 1;
    public static final long STABLE_AFTER_MILLIS = 60_000L;
    public static final int CRASH_LIMIT = 2;

    /** Манифест требует более новую платформу, чем установленная. */
    public static final class IncompatiblePlatformException extends IOException {
        public IncompatiblePlatformException(String train, int required, int supported) {
            super("Train " + train + " requires platformApi " + required + ", supported " + supported);
        }
    }

    /** Итог {@link #prepare()}. */
    public record Prepared(String train, List<Path> classpath, String rolledBackFrom, boolean dbRestored) {
    }

    private final TrainStore store;
    private final DbBackup backup;
    private final int platformApi;

    public Launcher(Path root, Path dataDir) {
        this(root, dataDir, PLATFORM_API);
    }

    Launcher(Path root, Path dataDir, int platformApi) {
        this.store = new TrainStore(root);
        this.backup = new DbBackup(root.toAbsolutePath(), dataDir);
        this.platformApi = platformApi;
    }

    public TrainStore store() {
        return store;
    }

    /**
     * Готовит запуск: учитывает краш прошлого процесса, откатывает при необходимости, проверяет текущий поезд.
     * Без поезда ({@code train == null}) приложение работает на seed-класспате установщика.
     */
    public Prepared prepare() throws IOException {
        LauncherState state = store.state();
        String rolledBackFrom = null;
        boolean dbRestored = false;
        if (state.running && !state.stable && !state.current.isEmpty()) {
            state.crashes++;
            state.save(store.stateFile());
            if (state.crashes >= CRASH_LIMIT) {
                rolledBackFrom = state.current;
                dbRestored |= rollbackAndRestore();
            }
        }
        while (true) {
            state = store.state();
            if (state.current.isEmpty()) {
                break;
            }
            try {
                checkCompatible(state.current);
                store.verify(state.current);
                break;
            } catch (IOException | RuntimeException e) {
                if (rolledBackFrom == null) {
                    rolledBackFrom = state.current;
                }
                dbRestored |= rollbackAndRestore();
            }
        }
        state = store.state();
        state.running = true;
        state.save(store.stateFile());
        if (state.current.isEmpty()) {
            return new Prepared(null, List.of(), rolledBackFrom, dbRestored);
        }
        return new Prepared(state.current, store.classpath(state.current), rolledBackFrom, dbRestored);
    }

    /**
     * Подготавливает и активирует поезд. Перед активацией, если версия схем БД растёт, делает бэкап данных.
     *
     * @throws IncompatiblePlatformException если нужна более новая платформа
     * @throws TrainStore.HashMismatchException если скачанный jar не совпал с манифестом
     */
    public void applyUpdate(String manifestJson, TrainStore.Fetcher fetcher) throws IOException {
        TrainManifest manifest = TrainManifest.parse(manifestJson);
        if (manifest.platformApi() > platformApi) {
            throw new IncompatiblePlatformException(manifest.train(), manifest.platformApi(), platformApi);
        }
        store.stage(manifestJson, fetcher);
        LauncherState state = store.state();
        boolean migrates = manifest.dbSchema() > state.dbSchema;
        if (migrates) {
            backup.backup(manifest.train());
        }
        store.activate(manifest.train());
        state = store.state();
        if (migrates) {
            state.backupOf = manifest.train();
        } else if (!state.backupOf.isEmpty()) {
            // Предыдущий бэкап относился к поезду, который теперь стал «предыдущим» — он остаётся валидным.
            state.backupOf = state.backupOf.equals(state.previous) ? state.backupOf : "";
        }
        state.dbSchema = Math.max(state.dbSchema, manifest.dbSchema());
        state.save(store.stateFile());
    }

    /** Процесс прожил окно стабильности: краши больше не засчитываются, бэкап БД не нужен. */
    public void markStable() throws IOException {
        LauncherState state = store.state();
        state.stable = true;
        state.crashes = 0;
        if (!state.backupOf.isEmpty()) {
            backup.discard(state.backupOf);
            state.backupOf = "";
        }
        state.save(store.stateFile());
    }

    /** Штатный выход процесса. */
    public void markCleanExit() throws IOException {
        LauncherState state = store.state();
        state.running = false;
        state.save(store.stateFile());
    }

    /**
     * Бандл не поднялся (исключение при инициализации графа/старта): поезд откатывается сразу и помечается плохим.
     *
     * @return поезд, к которому откатились (после него нужен рестарт JVM), либо {@code null}
     */
    public String reportBundleFailure() throws IOException {
        String before = store.state().current;
        if (before.isEmpty()) {
            return null;
        }
        rollbackAndRestore();
        String now = store.state().current;
        return now.isEmpty() ? null : now;
    }

    /**
     * Сообщает приложению текущий поезд и корень лаунчера через системные свойства
     * {@code heartbeat.train} и {@code heartbeat.launcher.root} (их читает app-update).
     */
    public void exposeToApp(Prepared prepared, Path root) {
        System.setProperty("heartbeat.launcher.root", root.toAbsolutePath().toString());
        if (prepared.train() != null) {
            System.setProperty("heartbeat.train", prepared.train());
        } else {
            System.clearProperty("heartbeat.train");
        }
    }

    public boolean isBad(String train) throws IOException {
        return store.state().bad.contains(train);
    }

    /** Загрузчик классов поверх родителя с jar бандлов поезда. */
    public static URLClassLoader newClassLoader(List<Path> classpath, ClassLoader parent) throws MalformedURLException {
        List<URL> urls = new ArrayList<>();
        for (Path jar : classpath) {
            urls.add(jar.toUri().toURL());
        }
        return new URLClassLoader(urls.toArray(new URL[0]), parent);
    }

    private void checkCompatible(String train) throws IOException {
        int required = store.readManifest(train).platformApi();
        if (required > platformApi) {
            throw new IncompatiblePlatformException(train, required, platformApi);
        }
    }

    /** Откат поезда с восстановлением бэкапа БД, если он сделан перед активацией откатываемого поезда. */
    private boolean rollbackAndRestore() throws IOException {
        LauncherState before = store.state();
        String bad = before.current;
        boolean restore = !bad.isEmpty() && before.backupOf.equals(bad) && backup.exists(bad);
        if (restore) {
            backup.restore(bad);
        }
        String target = store.rollback();
        LauncherState after = store.state();
        after.backupOf = restore ? "" : after.backupOf;
        if (restore) {
            backup.discard(bad);
        }
        if (target != null) {
            try {
                after.dbSchema = store.readManifest(target).dbSchema();
            } catch (IOException | RuntimeException e) {
                after.dbSchema = 0;
            }
        } else {
            after.dbSchema = 0;
        }
        after.save(store.stateFile());
        return restore;
    }
}
