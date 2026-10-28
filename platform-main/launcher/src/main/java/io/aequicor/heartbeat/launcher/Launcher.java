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
 * откат: его вместе с восстановлением БД выполняет следующий {@code prepare()}. Запрос отката записывается до
 * восстановления и снимается только записью самого отката (или когда откат недопустим), а бэкап удаляется лишь после
 * неё. Восстановление требует аренды хранилища ({@link DbBackup}): пока её держит другой процесс (задержавшийся воркер)
 * или замена сорвалась и откатилась ({@link DbBackup.DataUnchangedException}), откат откладывается — текущий поезд
 * остаётся, ожидающий поезд не активируется, следующий запуск пробует снова. Если замена оборвалась посередине и
 * данные частичные, {@code prepare()} бросает {@link IOException}: запуск на частичных данных не происходит, запрос и
 * бэкап сохраняются для следующего запуска.
 *
 * <p>Откат не опускает схему БД: поезд с {@code dbSchema} ниже версии, под которую уже могла мигрировать БД,
 * становится текущим только вместе с восстановлением бэкапа, сделанного перед активацией откатываемого поезда, и только
 * если его схема не ниже схемы самого бэкапа ({@code backupSchema}). Целевой поезд проверяется (манифест и хэши jar)
 * до восстановления: к повреждённому поезду не откатываемся и бэкап не трогаем.
 * Без такого бэкапа (и без предыдущего поезда) откат не выполняется: при крахах остаётся текущий поезд, а
 * повреждённый текущий поезд останавливает запуск {@link IOException} с просьбой переустановить приложение.
 *
 * <p>Все изменения состояния идут под {@link TrainStore#locked}. Второй экземпляр процесса (не владелец
 * {@code instance.lock}, см. {@link LauncherMain}) состояние не меняет и вызывает только {@link #prepareReadOnly()}.
 */
public final class Launcher {
    /**
     * Версия API платформы, которую поддерживает этот лаунчер. Уровень 2: поезд может нести запись
     * {@code kind=launcher}, которую лаунчер уровня 1 положил бы в classpath приложения как бандл.
     */
    public static final int PLATFORM_API = 2;
    public static final long STABLE_AFTER_MILLIS = 60_000L;
    /**
     * Системное свойство: пробный лаунчер отказывается стартовать, потому что операция с данными (откат с восстановлением
     * БД) ждёт подтверждённого лаунчера. Селектор не считает такой отказ крахом слота и запускает подтверждённый.
     */
    public static final String DEFERRED = "heartbeat.slot.deferred";
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

    /** Допустимый откат: поезд {@code bad} → {@code target}, восстанавливать ли бэкап и схема БД после отката. */
    private record Plan(String bad, String target, boolean restore, int schema) {
    }

    private final TrainStore store;
    private final DbBackup backup;
    private final int platformApi;
    private final boolean deferDataOperations;

    public Launcher(Path root, Path dataDir) {
        this(root, dataDir, PLATFORM_API, false);
    }

    /**
     * @param deferDataOperations лаунчер запущен как пробный слот и ещё не подтверждён: откат с восстановлением БД и
     *                            активация ожидающего поезда (бэкап БД перед миграцией) ждут подтверждения слота
     */
    public Launcher(Path root, Path dataDir, boolean deferDataOperations) {
        this(root, dataDir, PLATFORM_API, deferDataOperations);
    }

    Launcher(Path root, Path dataDir, int platformApi) {
        this(root, dataDir, platformApi, false);
    }

    Launcher(Path root, Path dataDir, int platformApi, boolean deferDataOperations) {
        this.deferDataOperations = deferDataOperations;
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
        boolean rollbackBlocked = false;
        if (!state.current.isEmpty()) {
            boolean crashed = state.running && !state.stable;
            if (crashed) {
                state.crashes++;
            }
            boolean rollback = state.rollbackRequested || crashed && state.crashes >= CRASH_LIMIT;
            // Запрос держится до записи отката (store.rollback снимает его): сбой восстановления его не теряет.
            state.rollbackRequested = rollback;
            state.save(store.stateFile());
            if (rollback && deferDataOperations) {
                // The rollback restores the DB and cannot be undone: the confirmed launcher does it at the next start.
                System.err.println("Train " + state.current + " rolls back after the launcher is confirmed");
                rollbackBlocked = true;
            } else if (rollback) {
                String from = state.current;
                try {
                    Rollback done = rollbackAndRestore();
                    if (done != null) {
                        rolledBackFrom = from;
                        dbRestored = done.restored();
                    } else {
                        dropRollbackRequest();
                        System.err.println("Train " + from + " fails, but there is no train to roll back to safely; "
                            + "keeping it");
                    }
                } catch (DbBackup.DataUnchangedException deferred) {
                    System.err.println("Train " + from + " rolls back at the next start: " + deferred.getMessage());
                    rollbackBlocked = true;
                } catch (IOException | RuntimeException failed) {
                    throw new IOException("Rollback of train " + from + " did not complete and is retried at the next "
                        + "start; the DB backup is kept (" + failed.getMessage() + ")", failed);
                }
            }
        }
        if (!rollbackBlocked && !deferDataOperations) {
            applyPending();
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
                if (deferDataOperations) {
                    keepRollbackRequest();
                    System.setProperty(DEFERRED, "true");
                    throw new IOException("Train " + state.current + " is damaged; its rollback restores data and "
                        + "waits until the launcher is confirmed (" + e.getMessage() + ")", e);
                }
                Rollback done;
                try {
                    done = rollbackAndRestore();
                } catch (DbBackup.StorageInUseException busy) {
                    keepRollbackRequest();
                    throw new IOException("Train " + state.current + " is damaged and its rollback restores data "
                        + "that another Heartbeat process still uses; close it and start again (" + e.getMessage()
                        + ")", busy);
                } catch (IOException | RuntimeException failed) {
                    keepRollbackRequest();
                    throw new IOException("Train " + state.current + " is damaged and its rollback did not complete; "
                        + "it is retried at the next start (" + e.getMessage() + "; " + failed.getMessage() + ")",
                        failed);
                }
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
     * уже текущий поезд пропускается. Каталог ожидающего поезда удаляется при любом исходе, кроме отказа бэкапа
     * мигрирующего поезда без изменения данных ({@link DbBackup.DataUnchangedException}: аренда хранилища занята,
     * небезопасная раскладка) — тогда поезд остаётся ожидающим до следующего запуска.
     */
    private void applyPending() throws IOException {
        try {
            store.deleteIncompletePending();
        } catch (IOException | RuntimeException e) {
            System.err.println("Incomplete pending trains were not removed: " + e);
        }
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
        boolean deferred = false;
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
        } catch (DbBackup.DataUnchangedException e) {
            deferred = true;
            System.err.println("Pending train " + newest.getParent().getFileName() + " is activated at a later start, "
                + "its DB backup was not made: " + e.getMessage());
        } catch (IOException | RuntimeException e) {
            System.err.println("Pending train " + newest.getParent().getFileName() + " not applied: " + e);
        } finally {
            if (!deferred) {
                TrainStore.deleteRecursively(newest.getParent());
            }
        }
    }

    /**
     * Подготавливает и активирует поезд. Перед активацией, если версия схем БД растёт, делает бэкап данных; активация
     * и привязка бэкапа записываются одной записью состояния. Бэкап снимается под арендой хранилища.
     *
     * @throws IncompatiblePlatformException если нужна более новая платформа
     * @throws DbBackup.DataUnchangedException если бэкап мигрирующего поезда не сделан — аренду хранилища держит
     *         другой процесс или раскладка каталогов небезопасна; поезд не активирован
     * @throws TrainStore.HashMismatchException если скачанный jar не совпал с манифестом
     */
    public void applyUpdate(String manifestJson, TrainStore.Fetcher fetcher) throws IOException {
        TrainManifest manifest = TrainManifest.parse(manifestJson);
        if (manifest.platformApi() > platformApi) {
            throw new IncompatiblePlatformException(manifest.train(), manifest.platformApi(), platformApi);
        }
        store.locked(() -> {
            store.stage(manifestJson, fetcher);
            int dataSchema = store.state().dbSchema;
            boolean migrates = manifest.dbSchema() > dataSchema;
            if (migrates) {
                backup.backup(manifest.train());
            }
            store.activate(manifest.train(), state -> {
                if (migrates) {
                    state.backupOf = manifest.train();
                    state.backupSchema = dataSchema;
                } else if (!state.backupOf.isEmpty() && !state.backupOf.equals(state.previous)) {
                    // Бэкап поезда, ставшего «предыдущим», остаётся валидным; остальные больше не нужны.
                    state.backupOf = "";
                    state.backupSchema = -1;
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
            String discarded = state.backupOf;
            state.backupOf = "";
            state.backupSchema = -1;
            state.save(store.stateFile());
            if (!discarded.isEmpty()) {
                discardQuietly(discarded);
            }
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
     * трогаются — их ещё держит этот процесс. После {@link #markStable()}, без предыдущего поезда и когда откат
     * отклонила бы проверка (повреждённый целевой поезд, схема БД опустилась бы) ничего не делает: поезд плохим не
     * помечается, процесс засчитывается крахом.
     *
     * @return поезд, к которому откатится следующий запуск (нужен рестарт JVM), либо {@code null}
     */
    public String reportBundleFailure() throws IOException {
        return store.locked(() -> {
            LauncherState state = store.state();
            if (state.current.isEmpty() || state.stable || plan(state) == null) {
                return null;
            }
            state.rollbackRequested = true;
            state.save(store.stateFile());
            return state.previous;
        });
    }

    /** Свойства для приложения без состояния лаунчера: поезда нет, запуск из seed установщика. */
    public static void exposeWithoutState(Path root) {
        System.setProperty("heartbeat.launcher.root", root.toAbsolutePath().toString());
        System.clearProperty("heartbeat.train.bad");
        System.clearProperty("heartbeat.train");
        System.clearProperty("heartbeat.train.channel");
    }

    /**
     * Сообщает приложению текущий поезд, корень лаунчера и плохие поезда через системные свойства
     * {@code heartbeat.train}, {@code heartbeat.launcher.root} и {@code heartbeat.train.bad} (через запятую; пусто —
     * свойство снято). Их читает app-update, чтобы не предлагать откаченные поезда снова. Поезд из beta-канала
     * ({@code channel} в его манифесте) добавляет {@code heartbeat.train.channel=beta}: по нему desktop показывает
     * приложение как Beta (имя и иконка); у поезда релиза свойство снято.
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
        if (prepared.train() != null && isBetaTrain(prepared.train())) {
            System.setProperty("heartbeat.train.channel", "beta");
        } else {
            System.clearProperty("heartbeat.train.channel");
        }
    }

    /** Манифест поезда читается без ошибок и называет beta-канал; нечитаемый манифест — не beta. */
    private boolean isBetaTrain(String train) {
        try {
            return store.readManifest(train).beta();
        } catch (IOException | RuntimeException e) {
            System.err.println("Channel of train " + train + " is unknown, showing the release app: " + e);
            return false;
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
     * Проверяет, допустим ли откат из состояния {@code state}: предыдущий поезд есть и цел (манифест читается, jar
     * совпадают по хэшу), а его схема БД не ниже схемы данных после отката — бэкапа ({@code backupSchema}), если он
     * восстанавливается, иначе текущей.
     *
     * @return план отката либо {@code null}, если откатываться нельзя
     */
    private Plan plan(LauncherState state) {
        String bad = state.current;
        String target = state.previous;
        if (bad.isEmpty() || target.isEmpty()) {
            return null;
        }
        int targetSchema;
        try {
            targetSchema = store.readManifest(target).dbSchema();
            store.verify(target);
        } catch (IOException | RuntimeException e) {
            System.err.println("Rollback target " + target + " is damaged, not rolling back: " + e);
            return null;
        }
        boolean restore = state.backupOf.equals(bad) && backup.exists(bad);
        int dataSchema;
        if (restore) {
            // Бэкап старого лаунчера без записанной схемы: снят при текущем предыдущем поезде.
            dataSchema = state.backupSchema >= 0 ? state.backupSchema : targetSchema;
        } else {
            dataSchema = state.dbSchema;
        }
        if (targetSchema < dataSchema) {
            return null;
        }
        // Целевой поезд при первом запуске мигрирует данные до своей схемы.
        return new Plan(bad, target, restore, Math.max(dataSchema, targetSchema));
    }

    /**
     * Откат поезда к предыдущему с восстановлением бэкапа БД, если он сделан перед активацией откатываемого поезда
     * (см. {@link #plan}). Бэкап удаляется после записи отката; сбой удаления только печатается.
     *
     * @return итог отката, либо {@code null}, если откат недопустим (состояние при этом не меняется)
     * @throws DbBackup.DataUnchangedException если восстановление отклонено или отменено (данные и состояние прежние)
     * @throws IOException если замена данных оборвалась (данные частичные) или откат не записан; бэкап сохраняется
     */
    private Rollback rollbackAndRestore() throws IOException {
        Plan plan = plan(store.state());
        if (plan == null) {
            return null;
        }
        if (plan.restore()) {
            backup.restore(plan.bad());
        }
        store.rollback(after -> {
            if (after.backupOf.equals(plan.bad())) {
                after.backupOf = "";
                after.backupSchema = -1;
            }
            after.dbSchema = plan.schema();
        });
        if (plan.restore()) {
            discardQuietly(plan.bad());
        }
        return new Rollback(plan.target(), plan.restore());
    }

    /** Откат не выполнен: запрос сохраняется до следующего {@link #prepare()}. */
    private void keepRollbackRequest() throws IOException {
        LauncherState state = store.state();
        state.rollbackRequested = true;
        state.save(store.stateFile());
    }

    /** Откат недопустим: запрос снимается, чтобы не блокировать отметку стабильности. */
    private void dropRollbackRequest() throws IOException {
        LauncherState state = store.state();
        state.rollbackRequested = false;
        state.save(store.stateFile());
    }

    private void discardQuietly(String train) {
        try {
            backup.discard(train);
        } catch (IOException | RuntimeException e) {
            System.err.println("DB backup of " + train + " was not removed: " + e);
        }
    }
}
