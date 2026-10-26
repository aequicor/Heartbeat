package io.aequicor.heartbeat.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Хранилище поездов в корне лаунчера. Раскладка: {@code store/<sha256>.jar} (jar общие для поездов),
 * {@code trains/<train>/manifest.json}, {@code staging/} (недокачанное), {@code pending/<train>/manifest.json}
 * (поезд, подготовленный app-update: все его jar уже в {@code store}; активирует его {@link Launcher#prepare()}),
 * {@code backups/<train>/} (бэкап БД), {@code state.properties} и {@code state.lock}.
 * Хранит не более {@link #KEEP_TRAINS} поездов: текущий и предыдущий; jar ожидающих поездов не удаляются.
 *
 * <p>Все изменения состояния и хранилища идут под {@link #locked}: блокировка внутри процесса (общая для всех
 * экземпляров с тем же корнем) и файловая блокировка {@code state.lock} между процессами. Вложенные вызовы в том же
 * потоке повторно не блокируют файл.
 */
public final class TrainStore {
    public static final int KEEP_TRAINS = 2;

    /** Действие под блокировкой состояния. */
    interface Locked<T> {
        T run() throws IOException;
    }

    private static final ConcurrentHashMap<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    /** Источник байт jar для бандла (сеть, кэш релиза или тестовая подмена). */
    public interface Fetcher {
        InputStream open(TrainManifest.Bundle bundle) throws IOException;
    }

    /** Скачанные данные не совпали с SHA-256 манифеста. */
    public static final class HashMismatchException extends IOException {
        public HashMismatchException(String jar, String expected, String actual) {
            super("SHA-256 mismatch for " + jar + ": expected " + expected + ", actual " + actual);
        }
    }

    private final Path root;

    public TrainStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    Path root() {
        return root;
    }

    Path pendingDir() {
        return root.resolve("pending");
    }

    /**
     * Выполняет {@code action} под блокировкой состояния: сначала внутри процесса, затем файловой {@code state.lock}
     * (ожидает, пока её держит другой процесс).
     */
    <T> T locked(Locked<T> action) throws IOException {
        ReentrantLock lock = LOCKS.computeIfAbsent(root, key -> new ReentrantLock());
        lock.lock();
        try {
            if (lock.getHoldCount() > 1) {
                return action.run();
            }
            Files.createDirectories(root);
            try (FileChannel channel = FileChannel.open(root.resolve("state.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                return action.run();
            }
        } finally {
            lock.unlock();
        }
    }

    Path stateFile() {
        return root.resolve("state.properties");
    }

    Path jarPath(String sha256) {
        return root.resolve("store").resolve(sha256.toLowerCase(Locale.ROOT) + ".jar");
    }

    Path manifestFile(String train) {
        return root.resolve("trains").resolve(train).resolve("manifest.json");
    }

    LauncherState state() throws IOException {
        return LauncherState.load(stateFile());
    }

    /**
     * Докачивает недостающие jar поезда в {@code staging}, проверяет SHA-256 каждого и переносит в {@code store},
     * затем записывает манифест. Уже лежащие в хранилище jar с верным хэшем не качаются повторно; jar с неверным
     * хэшем удаляется до скачивания, чтобы его можно было скачать заново и при сбое этой попытки.
     * Активным поезд не становится — для этого {@link #activate}.
     */
    public void stage(String manifestJson, Fetcher fetcher) throws IOException {
        locked(() -> {
            doStage(manifestJson, fetcher);
            return null;
        });
    }

    private void doStage(String manifestJson, Fetcher fetcher) throws IOException {
        TrainManifest manifest = TrainManifest.parse(manifestJson);
        Path staging = Files.createDirectories(root.resolve("staging").resolve(manifest.train()));
        Files.createDirectories(root.resolve("store"));
        try {
            for (TrainManifest.Bundle bundle : manifest.bundles()) {
                String sha = TrainManifest.normalizedSha(bundle);
                Path target = jarPath(sha);
                if (Files.isRegularFile(target)) {
                    if (sha256(target).equals(sha)) {
                        continue;
                    }
                    System.err.println("Store jar " + target.getFileName() + " is corrupted, fetching it again");
                    Files.deleteIfExists(target);
                }
                Path part = staging.resolve(sha + ".part");
                try (InputStream in = fetcher.open(bundle); OutputStream out = Files.newOutputStream(part)) {
                    in.transferTo(out);
                }
                String actual = sha256(part);
                if (!actual.equals(sha)) {
                    Files.deleteIfExists(part);
                    throw new HashMismatchException(bundle.jar(), sha, actual);
                }
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            Path manifestFile = manifestFile(manifest.train());
            Files.createDirectories(manifestFile.getParent());
            Path tmp = manifestFile.resolveSibling("manifest.json.tmp");
            Files.write(tmp, manifestJson.getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, manifestFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            deleteRecursively(staging);
        }
    }

    /** Health-check поезда: манифест читается, все jar на месте и их SHA-256 совпадают. */
    public void verify(String train) throws IOException {
        TrainManifest manifest = readManifest(train);
        for (TrainManifest.Bundle bundle : manifest.bundles()) {
            String sha = TrainManifest.normalizedSha(bundle);
            Path jar = jarPath(sha);
            if (!Files.isRegularFile(jar)) {
                throw new IOException("Missing jar " + bundle.jar() + " of train " + train);
            }
            String actual = sha256(jar);
            if (!actual.equals(sha)) {
                throw new HashMismatchException(bundle.jar(), sha, actual);
            }
        }
    }

    public TrainManifest readManifest(String train) throws IOException {
        return TrainManifest.parse(Files.readString(manifestFile(train), StandardCharsets.UTF_8));
    }

    /** Классpath поезда: jar бандлов в порядке манифеста. */
    public List<Path> classpath(String train) throws IOException {
        List<Path> result = new ArrayList<>();
        for (TrainManifest.Bundle bundle : readManifest(train).bundles()) {
            result.add(jarPath(TrainManifest.normalizedSha(bundle)));
        }
        return result;
    }

    /**
     * Делает уже подготовленный поезд текущим (прежний текущий становится предыдущим), сбрасывает счётчик крахов,
     * чистит всё, кроме двух последних поездов. Плохой поезд не активируется.
     */
    public void activate(String train) throws IOException {
        activate(train, state -> {
        });
    }

    /**
     * Как {@link #activate(String)}, но {@code update} правит состояние после переключения поезда — в той же
     * единственной записи, без промежуточного состояния на диске.
     */
    void activate(String train, Consumer<LauncherState> update) throws IOException {
        locked(() -> {
            LauncherState state = state();
            if (state.bad.contains(train)) {
                throw new IOException("Train " + train + " is marked bad");
            }
            verify(train);
            if (train.equals(state.current)) {
                return null;
            }
            state.previous = state.current;
            state.current = train;
            state.crashes = 0;
            state.running = false;
            state.stable = false;
            state.rollbackRequested = false;
            update.accept(state);
            state.save(stateFile());
            pruneQuietly();
            return null;
        });
    }

    /**
     * Откатывает весь поезд: текущий помечается плохим, предыдущий становится текущим.
     *
     * @return имя поезда, к которому откатились, либо {@code null}, если откатываться некуда
     *         (плохая метка при этом всё равно ставится)
     */
    public String rollback() throws IOException {
        return rollback(state -> {
        });
    }

    /** Как {@link #rollback()}, но {@code update} правит состояние после отката в той же записи. */
    String rollback(Consumer<LauncherState> update) throws IOException {
        return locked(() -> {
            LauncherState state = state();
            if (!state.current.isEmpty()) {
                state.bad.add(state.current);
            }
            String target = state.previous.isEmpty() ? null : state.previous;
            state.current = target == null ? "" : target;
            state.previous = "";
            state.crashes = 0;
            state.running = false;
            state.stable = true;
            state.rollbackRequested = false;
            update.accept(state);
            state.save(stateFile());
            pruneQuietly();
            return target;
        });
    }

    /**
     * Манифесты поездов, ожидающих активации ({@code pending/<train>/manifest.json}); каталоги без манифеста
     * (недописанные) не входят.
     */
    List<Path> pendingManifests() throws IOException {
        List<Path> result = new ArrayList<>();
        Path pending = pendingDir();
        if (!Files.isDirectory(pending)) {
            return result;
        }
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(pending)) {
            for (Path dir : dirs) {
                Path manifest = dir.resolve("manifest.json");
                if (Files.isRegularFile(manifest)) {
                    result.add(manifest);
                }
            }
        }
        return result;
    }

    /**
     * Удаляет каталоги {@code pending/<train>} без {@code manifest.json}: недописанные app-update (например, только
     * {@code manifest.json.tmp}). Вызывается владельцем при старте, когда app-update ещё не работает. Сбой удаления
     * только печатается.
     */
    void deleteIncompletePending() throws IOException {
        Path pending = pendingDir();
        if (!Files.isDirectory(pending)) {
            return;
        }
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(pending)) {
            for (Path dir : dirs) {
                if (!Files.isRegularFile(dir.resolve("manifest.json"))) {
                    tryDelete(dir);
                }
            }
        }
    }

    /**
     * Удаляет поезда, jar и бэкапы БД, не входящие в текущий и предыдущий поезд (в том числе подготовленные, но не
     * активированные). Jar поездов из {@code pending/} сохраняются. Очистка — best-effort: файл, который не удаётся
     * удалить (например, jar, открытый задержавшимся воркером на Windows), печатается в stderr и пропускается; если
     * не читается манифест сохраняемого поезда, jar не удаляются вовсе.
     *
     * @throws IOException только если не читается состояние
     */
    void prune() throws IOException {
        locked(() -> {
            doPrune();
            return null;
        });
    }

    /** {@link #prune()} после уже записанного состояния: никакой сбой очистки не отменяет операцию. */
    private void pruneQuietly() {
        try {
            prune();
        } catch (IOException | RuntimeException e) {
            System.err.println("Launcher store cleanup failed, retried at the next change: " + e);
        }
    }

    private static void tryDelete(Path path) {
        try {
            deleteRecursively(path);
        } catch (IOException | RuntimeException e) {
            System.err.println("Could not delete " + path + ": " + e);
        }
    }

    private void doPrune() throws IOException {
        LauncherState state = state();
        Set<String> keepTrains = new HashSet<>();
        if (!state.current.isEmpty()) {
            keepTrains.add(state.current);
        }
        if (!state.previous.isEmpty()) {
            keepTrains.add(state.previous);
        }
        Set<String> keepJars = new HashSet<>();
        boolean jarsKnown = true;
        Path trains = root.resolve("trains");
        if (Files.isDirectory(trains)) {
            try (DirectoryStream<Path> dirs = Files.newDirectoryStream(trains)) {
                for (Path dir : dirs) {
                    String name = dir.getFileName().toString();
                    if (!keepTrains.contains(name)) {
                        tryDelete(dir);
                    }
                }
            } catch (IOException e) {
                System.err.println("Could not list trains for cleanup: " + e);
            }
        }
        for (String train : keepTrains) {
            if (Files.exists(manifestFile(train))) {
                try {
                    for (TrainManifest.Bundle bundle : readManifest(train).bundles()) {
                        keepJars.add(TrainManifest.normalizedSha(bundle) + ".jar");
                    }
                } catch (IOException | RuntimeException e) {
                    jarsKnown = false;
                    System.err.println("Manifest of kept train " + train + " is unreadable, jars are kept: " + e);
                }
            }
        }
        for (Path pending : pendingManifests()) {
            try {
                TrainManifest manifest = TrainManifest.parse(Files.readString(pending, StandardCharsets.UTF_8));
                for (TrainManifest.Bundle bundle : manifest.bundles()) {
                    keepJars.add(TrainManifest.normalizedSha(bundle) + ".jar");
                }
            } catch (IOException | RuntimeException e) {
                // Нечитаемый ожидающий манифест не будет активирован: его jar не держим.
                System.err.println("Pending manifest " + pending + " is unreadable: " + e);
            }
        }
        Path backups = root.resolve("backups");
        if (Files.isDirectory(backups)) {
            try (DirectoryStream<Path> dirs = Files.newDirectoryStream(backups)) {
                for (Path dir : dirs) {
                    if (!keepTrains.contains(dir.getFileName().toString())) {
                        tryDelete(dir);
                    }
                }
            } catch (IOException e) {
                System.err.println("Could not list DB backups for cleanup: " + e);
            }
        }
        Path store = root.resolve("store");
        if (jarsKnown && Files.isDirectory(store)) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(store)) {
                for (Path file : files) {
                    if (!keepJars.contains(file.getFileName().toString())) {
                        tryDelete(file);
                    }
                }
            } catch (IOException e) {
                System.err.println("Could not list the jar store for cleanup: " + e);
            }
        }
    }

    static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream din = new DigestInputStream(in, digest)) {
                din.transferTo(OutputStream.nullOutputStream());
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Удаляет файл или каталог с содержимым; по symlink не переходит — удаляется сама ссылка. */
    static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            try (DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
                for (Path child : children) {
                    deleteRecursively(child);
                }
            }
        }
        Files.deleteIfExists(path);
    }
}
