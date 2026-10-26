package io.aequicor.heartbeat.launcher;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Бэкап и восстановление каталога данных (Room/KV) перед миграцией схем: {@code backups/<train>/}.
 *
 * <p>{@code dataDir} — корень хранилища desktop ({@code desktopStorageRoot()}). Процессы, работающие с данными
 * (приложение и воркеры {@code --heartbeat-*}, в том числе пережившие приложение), держат аренду хранилища —
 * OS-блокировку {@value #OWNER_DIR}/{@value #OWNER_LOCK} ({@code DesktopStorageOwner} в {@code platform-main:desktop}).
 * Бэкап и восстановление создают файл аренды, если его нет, берут блокировку без ожидания и держат её до конца
 * операции; если её держит другой процесс, операция отклоняется ({@link StorageInUseException}), данные не трогаются.
 * Каталог аренды не входит ни в бэкап, ни в замену.
 *
 * <p>Раскладка проверяется до любой операции: если корень лаунчера или {@code backups/} лежит внутри
 * {@code dataDir} либо {@code dataDir} — внутри корня лаунчера (сравниваются реальные пути), бэкап и восстановление
 * отклоняются ({@link UnsafeLayoutException}).
 *
 * <p>Замена при восстановлении не атомарна, но восстановима: бэкап копируется в соседний каталог
 * {@code .<data>.restoring}, затем старые записи переименованием уходят в соседний {@code .<data>.replaced}, и только
 * потом копия переносится в {@code dataDir}. Сбой посередине откатывает перенос обратно ({@link DataUnchangedException});
 * если и это не удалось, бросается обычный {@link IOException}: данные частичные, прежние лежат в
 * {@code .<data>.replaced}. Бэкап в обоих случаях не трогается — его удаляет только вызывающий после записи отката,
 * поэтому следующий запуск повторяет замену.
 *
 * <p>Ограничение: процесс, открывший файлы данных без аренды, не обнаруживается.
 */
final class DbBackup {
    static final String OWNER_DIR = "execution-owner";
    static final String OWNER_LOCK = "owner.lock";

    /** Операция отклонена или отменена до конца: данные в {@code dataDir} остались прежними, её можно повторить. */
    static class DataUnchangedException extends IOException {
        DataUnchangedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Хранилище занято другим процессом (аренда {@code execution-owner/owner.lock} удерживается). */
    static final class StorageInUseException extends DataUnchangedException {
        StorageInUseException(Path lock) {
            super("Data storage is in use by another process (" + lock + ")", null);
        }
    }

    /** Корень лаунчера и каталог данных пересекаются: бэкап или замена могли бы задеть друг друга. */
    static final class UnsafeLayoutException extends DataUnchangedException {
        UnsafeLayoutException(String message) {
            super(message, null);
        }
    }

    /**
     * Каналы файла аренды, на которых {@code tryLock} бросил {@link OverlappingFileLockException} (блокировку держит
     * этот же JVM через другой канал). Их не закрывают: на POSIX закрытие любого дескриптора файла снимает все
     * блокировки процесса на нём, включая чужую аренду. Утечка — по дескриптору на отклонённую попытку.
     */
    private static final List<FileChannel> RETAINED = Collections.synchronizedList(new ArrayList<>());

    private final Path root;
    private final Path backups;
    private final Path dataDir;

    DbBackup(Path root, Path dataDir) {
        this.root = root;
        this.backups = root.resolve("backups");
        this.dataDir = dataDir;
    }

    /**
     * Копирует {@code dataDir} (без каталога аренды) в бэкап поезда {@code train} под арендой хранилища, заменяя
     * прежний бэкап этого поезда.
     *
     * @throws StorageInUseException если аренду хранилища держит другой процесс (ничего не сделано)
     * @throws UnsafeLayoutException если корень лаунчера и данные пересекаются (ничего не сделано)
     */
    void backup(String train) throws IOException {
        checkLayout();
        Files.createDirectories(dataDir);
        Path data = dataDir.toRealPath();
        try (Lease ignored = Lease.take(data)) {
            Path target = backups.resolve(train);
            TrainStore.deleteRecursively(target);
            Files.createDirectories(target);
            copy(data, target, true);
        }
    }

    boolean exists(String train) {
        return Files.isDirectory(backups.resolve(train), LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * Заменяет {@code dataDir} содержимым бэкапа под арендой хранилища; данные, записанные после бэкапа, теряются.
     * Бэкап не удаляется.
     *
     * @throws DataUnchangedException если замена отклонена (аренда занята, небезопасная раскладка, нет бэкапа) или
     *         отменена после сбоя — данные прежние
     * @throws IOException если замена оборвалась и не откатилась: данные частичные, повторить восстановление
     */
    void restore(String train) throws IOException {
        checkLayout();
        Path source = backups.resolve(train);
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new DataUnchangedException("No DB backup for train " + train, null);
        }
        Files.createDirectories(dataDir);
        Path data = dataDir.toRealPath();
        try (Lease ignored = Lease.take(data)) {
            Path staged = data.resolveSibling("." + data.getFileName() + ".restoring");
            Path replaced = data.resolveSibling("." + data.getFileName() + ".replaced");
            try {
                // Остатки прошлой оборванной замены: прежние данные откатываемого поезда больше не нужны.
                TrainStore.deleteRecursively(staged);
                TrainStore.deleteRecursively(replaced);
                Files.createDirectories(staged);
                Files.createDirectories(replaced);
                copy(source, staged, true);
            } catch (IOException | RuntimeException e) {
                deleteQuietly(staged);
                throw new DataUnchangedException("DB backup of " + train + " was not staged", e);
            }
            swap(data, staged, replaced);
            deleteQuietly(replaced);
            deleteQuietly(staged);
        }
    }

    void discard(String train) throws IOException {
        TrainStore.deleteRecursively(backups.resolve(train));
    }

    /** Переносит записи {@code data} в {@code replaced}, затем записи {@code staged} в {@code data}; при сбое — назад. */
    private static void swap(Path data, Path staged, Path replaced) throws IOException {
        List<String> moved = new ArrayList<>();
        List<String> installed = new ArrayList<>();
        try {
            for (String name : names(data)) {
                if (!name.equals(OWNER_DIR)) {
                    Files.move(data.resolve(name), replaced.resolve(name));
                    moved.add(name);
                }
            }
            for (String name : names(staged)) {
                Files.move(staged.resolve(name), data.resolve(name));
                installed.add(name);
            }
        } catch (IOException | RuntimeException e) {
            boolean reverted = true;
            for (int i = installed.size() - 1; i >= 0; i--) {
                reverted &= moveBack(data.resolve(installed.get(i)), staged.resolve(installed.get(i)), e);
            }
            for (int i = moved.size() - 1; i >= 0; i--) {
                reverted &= moveBack(replaced.resolve(moved.get(i)), data.resolve(moved.get(i)), e);
            }
            if (reverted) {
                throw new DataUnchangedException("Data was not replaced, the previous data is back in " + data, e);
            }
            throw new IOException("Data replacement failed midway: " + data + " is incomplete, the previous data is in "
                + replaced, e);
        }
    }

    private static boolean moveBack(Path from, Path to, Exception failure) {
        try {
            Files.move(from, to);
            return true;
        } catch (IOException | RuntimeException e) {
            failure.addSuppressed(e);
            return false;
        }
    }

    /** Имена записей каталога, отсортированные: список читается до переноса, порядок — детерминированный. */
    private static List<String> names(Path dir) throws IOException {
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(dir)) {
            for (Path child : children) {
                names.add(child.getFileName().toString());
            }
        }
        Collections.sort(names);
        return names;
    }

    private static void deleteQuietly(Path path) {
        try {
            TrainStore.deleteRecursively(path);
        } catch (IOException | RuntimeException e) {
            System.err.println("Temporary data directory " + path + " was not removed: " + e);
        }
    }

    /** Отклоняет раскладку, при которой корень лаунчера и {@code dataDir} вложены друг в друга. */
    private void checkLayout() throws IOException {
        Path data = realPath(dataDir);
        Path launcher = realPath(root);
        Path backupRoot = realPath(backups);
        if (launcher.startsWith(data) || backupRoot.startsWith(data) || data.startsWith(launcher)) {
            throw new UnsafeLayoutException("Launcher root " + launcher + " and data directory " + data
                + " overlap; set " + LauncherMain.DATA_DIR + " to a directory outside the launcher root");
        }
    }

    /** Реальный путь: ближайший существующий предок разрешается с symlink, остаток добавляется нормализованным. */
    static Path realPath(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path existing = absolute;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return absolute;
        }
        return existing.toRealPath().resolve(existing.relativize(absolute)).normalize();
    }

    private static void copy(Path from, Path to, boolean skipOwner) throws IOException {
        try (DirectoryStream<Path> children = Files.newDirectoryStream(from)) {
            for (Path child : children) {
                if (skipOwner && child.getFileName().toString().equals(OWNER_DIR)) {
                    continue;
                }
                Path dest = to.resolve(child.getFileName().toString());
                if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(dest);
                    copy(child, dest, false);
                } else {
                    Files.copy(child, dest, StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS);
                }
            }
        }
    }

    /** Аренда хранилища на время операции: блокировка {@code execution-owner/owner.lock} (файл создаётся). */
    private static final class Lease implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;

        private Lease(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        static Lease take(Path data) throws IOException {
            Path file = Files.createDirectories(data.resolve(OWNER_DIR)).resolve(OWNER_LOCK);
            FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                // Блокировку держит этот же JVM: закрытие канала сняло бы её на POSIX — канал не закрывается.
                RETAINED.add(channel);
                throw new StorageInUseException(file);
            } catch (IOException | RuntimeException e) {
                channel.close();
                throw e;
            }
            if (lock == null) {
                channel.close();
                throw new StorageInUseException(file);
            }
            return new Lease(channel, lock);
        }

        @Override
        public void close() throws IOException {
            try {
                lock.release();
            } finally {
                channel.close();
            }
        }
    }
}
