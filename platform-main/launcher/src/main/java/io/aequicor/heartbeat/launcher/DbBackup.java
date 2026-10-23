package io.aequicor.heartbeat.launcher;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Бэкап и восстановление каталога данных (Room/KV) перед миграцией схем: {@code backups/<train>/}. */
final class DbBackup {
    private final Path backups;
    private final Path dataDir;

    DbBackup(Path root, Path dataDir) {
        this.backups = root.resolve("backups");
        this.dataDir = dataDir;
    }

    /** Копирует {@code dataDir} в бэкап поезда {@code train}, заменяя прежний бэкап. */
    void backup(String train) throws IOException {
        Path target = backups.resolve(train);
        TrainStore.deleteRecursively(target);
        Files.createDirectories(target);
        if (Files.isDirectory(dataDir)) {
            copy(dataDir, target);
        }
    }

    boolean exists(String train) {
        return Files.isDirectory(backups.resolve(train));
    }

    /** Заменяет {@code dataDir} содержимым бэкапа; данные, записанные после бэкапа, теряются. */
    void restore(String train) throws IOException {
        Path source = backups.resolve(train);
        if (!Files.isDirectory(source)) {
            throw new IOException("No DB backup for train " + train);
        }
        TrainStore.deleteRecursively(dataDir);
        Files.createDirectories(dataDir);
        copy(source, dataDir);
    }

    void discard(String train) throws IOException {
        TrainStore.deleteRecursively(backups.resolve(train));
    }

    private static void copy(Path from, Path to) throws IOException {
        try (DirectoryStream<Path> children = Files.newDirectoryStream(from)) {
            for (Path child : children) {
                Path dest = to.resolve(child.getFileName().toString());
                if (Files.isDirectory(child)) {
                    Files.createDirectories(dest);
                    copy(child, dest);
                } else {
                    Files.copy(child, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
