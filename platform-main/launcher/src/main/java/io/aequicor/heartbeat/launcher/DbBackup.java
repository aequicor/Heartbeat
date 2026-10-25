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

/**
 * Бэкап и восстановление каталога данных (Room/KV) перед миграцией схем: {@code backups/<train>/}.
 *
 * <p>{@code dataDir} — корень хранилища desktop ({@code desktopStorageRoot()}). Процессы, работающие с данными
 * (приложение и воркеры {@code --heartbeat-*}, в том числе пережившие приложение), держат аренду хранилища —
 * OS-блокировку {@value #OWNER_DIR}/{@value #OWNER_LOCK} ({@code DesktopStorageOwner} в {@code platform-main:desktop}).
 * Восстановление берёт эту блокировку без ожидания и держит её до конца замены; если её держит другой процесс,
 * восстановление отклоняется ({@link StorageInUseException}), данные не трогаются. Каталог аренды не входит ни в бэкап,
 * ни в замену.
 *
 * <p>Ограничение: процесс, открывший файлы данных без аренды, не обнаруживается. Замена не атомарна: бэкап сначала
 * копируется в соседний временный каталог, затем старые файлы удаляются и новые переносятся переименованием; сбой
 * посередине оставляет частичные данные, но бэкап и запрос отката сохраняются, и следующий запуск повторяет замену.
 */
final class DbBackup {
    static final String OWNER_DIR = "execution-owner";
    static final String OWNER_LOCK = "owner.lock";

    /** Хранилище занято другим процессом (аренда {@code execution-owner/owner.lock} удерживается). */
    static final class StorageInUseException extends IOException {
        StorageInUseException(Path lock) {
            super("Data storage is in use by another process (" + lock + ")");
        }
    }

    private final Path backups;
    private final Path dataDir;

    DbBackup(Path root, Path dataDir) {
        this.backups = root.resolve("backups");
        this.dataDir = dataDir;
    }

    /** Копирует {@code dataDir} (без каталога аренды) в бэкап поезда {@code train}, заменяя прежний бэкап. */
    void backup(String train) throws IOException {
        Path target = backups.resolve(train);
        TrainStore.deleteRecursively(target);
        Files.createDirectories(target);
        if (Files.isDirectory(dataDir)) {
            copy(dataDir, target, true);
        }
    }

    boolean exists(String train) {
        return Files.isDirectory(backups.resolve(train));
    }

    /**
     * Заменяет {@code dataDir} содержимым бэкапа под арендой хранилища; данные, записанные после бэкапа, теряются.
     *
     * @throws StorageInUseException если аренду хранилища держит другой процесс (данные не тронуты)
     */
    void restore(String train) throws IOException {
        Path source = backups.resolve(train);
        if (!Files.isDirectory(source)) {
            throw new IOException("No DB backup for train " + train);
        }
        Files.createDirectories(dataDir);
        Path data = dataDir.toRealPath();
        try (Lease ignored = Lease.take(data)) {
            Path staged = data.resolveSibling("." + data.getFileName() + ".restoring");
            TrainStore.deleteRecursively(staged);
            Files.createDirectories(staged);
            try {
                copy(source, staged, true);
                try (DirectoryStream<Path> children = Files.newDirectoryStream(data)) {
                    for (Path child : children) {
                        if (!isOwnerDir(child)) {
                            TrainStore.deleteRecursively(child);
                        }
                    }
                }
                try (DirectoryStream<Path> children = Files.newDirectoryStream(staged)) {
                    for (Path child : children) {
                        Files.move(child, data.resolve(child.getFileName().toString()));
                    }
                }
            } finally {
                TrainStore.deleteRecursively(staged);
            }
        }
    }

    void discard(String train) throws IOException {
        TrainStore.deleteRecursively(backups.resolve(train));
    }

    private static boolean isOwnerDir(Path child) {
        return child.getFileName().toString().equals(OWNER_DIR);
    }

    private static void copy(Path from, Path to, boolean skipOwner) throws IOException {
        try (DirectoryStream<Path> children = Files.newDirectoryStream(from)) {
            for (Path child : children) {
                if (skipOwner && isOwnerDir(child)) {
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

    /** Аренда хранилища на время замены: блокировка {@code execution-owner/owner.lock}, если файл существует. */
    private static final class Lease implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;

        private Lease(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        /**
         * Без файла аренды хранилище никто не держит: владелец создаёт его до блокировки, а новых владельцев, пока
         * лаунчер держит {@code instance.lock}, не появляется.
         */
        static Lease take(Path data) throws IOException {
            Path file = data.resolve(OWNER_DIR).resolve(OWNER_LOCK);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                return new Lease(null, null);
            }
            FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE);
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                lock = null;
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
            if (channel == null) {
                return;
            }
            try {
                lock.release();
            } finally {
                channel.close();
            }
        }
    }
}
