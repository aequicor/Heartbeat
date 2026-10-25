package io.aequicor.heartbeat.launcher;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Лаунчер платформы (чистая JVM): строит classpath текущего поезда, активирует поезд, подготовленный app-update,
 * считает краши свежего поезда, откатывает поезд целиком и восстанавливает бэкап БД.
 *
 * <p>Жизненный цикл процесса: {@link #prepare()} при старте → {@link #markStable()} через
 * {@link #STABLE_AFTER_MILLIS} жизни → {@link #markCleanExit()} при штатном выходе. Процесс, снятый без
 * {@code markCleanExit} до {@code markStable}, считается крахом свежего поезда; {@link #CRASH_LIMIT} краха подряд —
 * откат. Исключение при инициализации бандла ({@link #reportBundleFailure}) до окна стабильности только запрашивает
 * откат: его вместе с восстановлением БД выполняет следующий {@code prepare()}, когда файлы данных уже никто не держит.
 *
 * <p>Откат не опускает схему БД: поезд с {@code dbSchema} ниже версии, под которую уже могла мигрировать БД,
 * становится текущим только вместе с восстановлением бэкапа, сделанного перед активацией откатываемого поезда.
 * Без такого бэкапа (и без предыдущего поезда) откат не выполняется: при крахах остаётся текущий поезд, а
 * повреждённый текущий поезд останавливает запуск {@link IOException} с просьбой переустановить приложение.
 *
 * <p>Все изменения состояния идут под {@link TrainStore#locked}. Второй экземпляр процесса (не владелец
 * {@code instance.lock}, см. {@link LauncherMain}) состояние не меняет и вызывает только {@link #prepareReadOnly()}.
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

    /** Выполненный откат: к какому поезду и восстановлен ли бэкап БД. */
    private record Rollback(String target, boolean restored) {
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
     * Готовит запуск: учитывает краш прошлого процесса и запрошенный откат, откатывает при необходимости, активирует
     * ожидающий поезд из {@code pending/}, проверяет текущий поезд. Без поезда ({@code train == null}) приложение
     * работает на seed-класспате установщика.
     *
     * @throws IOException если текущий поезд повреждён, а безопасно откатиться некуда (нужна переустановка)
     */
    public Prepared prepare() throws IOException {
        return store.locked(this::doPrepare);
    }

    private Prepared doPrepare() throws IOException {
        LauncherState state = store.state();
        String rolledBackFrom = null;
        boolean dbRestored = false;
        if (!state.current.isEmpty()) {
            boolean crashed = state.running && !state.stable;
            if (crashed) {
                state.crashes++;
            }
            boolean rollback = state.rollbackRequested || crashed && state.crashes >= CRASH_LIMIT;
            state.rollbackRequested = false;
            state.save(store.stateFile());
            if (rollback) {
                String from = state.current;
                Rollback done = rollbackAndRestore();
                if (done != null) {
                    rolledBackFrom = from;
                    dbRestored = done.restored();
                } else {
                    System.err.println("Train " + from + " fails, but there is no train to roll back to safely; "
                        + "keeping it");
                }
            }
        }
        applyPending();
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
                Rollback done = rollbackAndRestore();
                if (done == null) {
                    throw new IOException("Train " + state.current + " is damaged and there is no train to roll back "
                        + "to without losing data; reinstall Heartbeat (" + e.getMessage() + ")", e);
                }
                if (rolledBackFrom == null) {
                    rolledBackFrom = state.current;
                }
                dbRestored |= done.restored();
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
     * Запуск второго экземпляра: текущий поезд без изменения состояния — без учёта крахов, отката, активации
     * ожидающего поезда и проверки хэшей.
     */
    public Prepared prepareReadOnly() throws IOException {
        LauncherState state = store.state();
        if (state.current.isEmpty()) {
            return new Prepared(null, List.of(), null, false);
        }
        return new Prepared(state.current, store.classpath(state.current), null, false);
    }

    /**
     * Активирует самый свежий поезд из {@code pending/} (по времени изменения каталога), остальные удаляет. Все jar
     * ожидающего поезда уже в {@code store}: недостающий jar — ошибка, сеть не используется. Плохой, несовместимый или
     * уже текущий поезд пропускается. Каталог ожидающего поезда удаляется при любом исходе.
     */
    private void applyPending() throws IOException {
        List<Path> manifests = store.pendingManifests();
        Path newest = null;
        FileTime newestTime = null;
        for (Path manifest : manifests) {
            FileTime time = Files.getLastModifiedTime(manifest.getParent());
            if (newest == null || time.compareTo(newestTime) > 0) {
                newest = manifest;
                newestTime = time;
            }
        }
        for (Path manifest : manifests) {
            if (manifest != newest) {
                TrainStore.deleteRecursively(manifest.getParent());
            }
        }
        if (newest == null) {
            return;
        }
        try {
            String json = Files.readString(newest, StandardCharsets.UTF_8);
            TrainManifest manifest = TrainManifest.parse(json);
            LauncherState state = store.state();
            if (state.bad.contains(manifest.train())) {
                System.err.println("Pending train " + manifest.train() + " is marked bad, skipped");
            } else if (!manifest.train().equals(state.current)) {
                applyUpdate(json, bundle -> {
                    throw new IOException("Jar " + bundle.jar() + " of pending train " + manifest.train()
                        + " is missing from the store");
                });
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("Pending train " + newest.getParent().getFileName() + " not applied: " + e);
        } finally {
            TrainStore.deleteRecursively(newest.getParent());
        }
    }

    /**
     * Подготавливает и активирует поезд. Перед активацией, если версия схем БД растёт, делает бэкап данных; активация
     * и привязка бэкапа записываются одной записью состояния.
     *
     * @throws IncompatiblePlatformException если нужна более новая платформа
     * @throws TrainStore.HashMismatchException если скачанный jar не совпал с манифестом
     */
    public void applyUpdate(String manifestJson, TrainStore.Fetcher fetcher) throws IOException {
        TrainManifest manifest = TrainManifest.parse(manifestJson);
        if (manifest.platformApi() > platformApi) {
            throw new IncompatiblePlatformException(manifest.train(), manifest.platformApi(), platformApi);
        }
        store.locked(() -> {
            store.stage(manifestJson, fetcher);
            boolean migrates = manifest.dbSchema() > store.state().dbSchema;
            if (migrates) {
                backup.backup(manifest.train());
            }
            store.activate(manifest.train(), state -> {
                if (migrates) {
                    state.backupOf = manifest.train();
                } else if (!state.backupOf.isEmpty()) {
                    // Предыдущий бэкап относился к поезду, который теперь стал «предыдущим» — он остаётся валидным.
                    state.backupOf = state.backupOf.equals(state.previous) ? state.backupOf : "";
                }
                state.dbSchema = Math.max(state.dbSchema, manifest.dbSchema());
            });
            return null;
        });
    }

    /**
     * Процесс прожил окно стабильности: краши больше не засчитываются, бэкап БД не нужен. Пока запрошен откат, поезд
     * стабильным не считается (бэкап нужен откату).
     */
    public void markStable() throws IOException {
        store.locked(() -> {
            LauncherState state = store.state();
            if (state.rollbackRequested) {
                return null;
            }
            state.stable = true;
            state.crashes = 0;
            if (!state.backupOf.isEmpty()) {
                backup.discard(state.backupOf);
                state.backupOf = "";
            }
            state.save(store.stateFile());
            return null;
        });
    }

    /** Штатный выход процесса. */
    public void markCleanExit() throws IOException {
        store.locked(() -> {
            LauncherState state = store.state();
            state.running = false;
            state.save(store.stateFile());
            return null;
        });
    }

    /**
     * Бандл не поднялся (исключение при инициализации графа/старта) до окна стабильности: запрашивает откат поезда,
     * который выполнит следующий {@link #prepare()} (с восстановлением БД и плохой меткой). Файлы данных здесь не
     * трогаются — их ещё держит этот процесс. После {@link #markStable()} и без предыдущего поезда ничего не делает:
     * единственный поезд плохим не помечается, процесс засчитывается крахом.
     *
     * @return поезд, к которому откатится следующий запуск (нужен рестарт JVM), либо {@code null}
     */
    public String reportBundleFailure() throws IOException {
        return store.locked(() -> {
            LauncherState state = store.state();
            if (state.current.isEmpty() || state.stable || state.previous.isEmpty()) {
                return null;
            }
            state.rollbackRequested = true;
            state.save(store.stateFile());
            return state.previous;
        });
    }

    /**
     * Сообщает приложению текущий поезд, корень лаунчера и плохие поезда через системные свойства
     * {@code heartbeat.train}, {@code heartbeat.launcher.root} и {@code heartbeat.train.bad} (через запятую; пусто —
     * свойство снято). Их читает app-update, чтобы не предлагать откаченные поезда снова.
     */
    public void exposeToApp(Prepared prepared, Path root) throws IOException {
        System.setProperty("heartbeat.launcher.root", root.toAbsolutePath().toString());
        Set<String> bad = store.state().bad;
        if (bad.isEmpty()) {
            System.clearProperty("heartbeat.train.bad");
        } else {
            System.setProperty("heartbeat.train.bad", String.join(",", bad));
        }
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

    /**
     * Откат поезда к предыдущему с восстановлением бэкапа БД, если он сделан перед активацией откатываемого поезда.
     * Поезд со схемой БД ниже текущей версии становится текущим только вместе с восстановлением бэкапа.
     *
     * @return итог отката, либо {@code null}, если предыдущего поезда нет или откат опустил бы схему БД без бэкапа
     *         (состояние при этом не меняется)
     */
    private Rollback rollbackAndRestore() throws IOException {
        LauncherState before = store.state();
        String bad = before.current;
        String target = before.previous;
        if (bad.isEmpty() || target.isEmpty()) {
            return null;
        }
        boolean restore = before.backupOf.equals(bad) && backup.exists(bad);
        int targetSchema;
        try {
            targetSchema = store.readManifest(target).dbSchema();
        } catch (IOException | RuntimeException e) {
            targetSchema = -1;
        }
        if (targetSchema < before.dbSchema && !restore) {
            return null;
        }
        if (restore) {
            backup.restore(bad);
        }
        int schema = restore ? Math.max(targetSchema, 0) : Math.max(before.dbSchema, targetSchema);
        store.rollback(after -> {
            if (after.backupOf.equals(bad)) {
                after.backupOf = "";
            }
            after.dbSchema = schema;
        });
        if (restore) {
            backup.discard(bad);
        }
        return new Rollback(target, restore);
    }
}
