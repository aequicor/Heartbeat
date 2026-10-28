package io.aequicor.heartbeat.selector;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Раскладка слотов на диске: {@code slots/A/launcher.jar}, {@code slots/B/launcher.jar}, {@code slots/state.properties}
 * и {@code slots/owner.lock} — блокировка владельца слотов на всё время процесса.
 */
final class SlotStore {
    /** Имена слотов; соседний слот — другой элемент списка. */
    static final List<String> SLOTS = List.of("A", "B");
    static final String JAR_NAME = "launcher.jar";

    /**
     * Блокировки владельца, которые держит этот процесс. Второй дескриптор того же файла открывать нельзя: закрытие
     * любого дескриптора снимает блокировки процесса на этот файл (POSIX), поэтому второй владелец отказывается заранее.
     */
    private static final Set<Path> HELD = ConcurrentHashMap.newKeySet();

    private final Path dir;
    /** Держится до конца процесса (поле не даёт сборщику освободить блокировку). */
    private FileChannel ownerLock;

    SlotStore(Path dir) {
        this.dir = dir.toAbsolutePath();
    }

    Path dir() {
        return dir;
    }

    Path stateFile() {
        return dir.resolve("state.properties");
    }

    Path slotDir(String slot) {
        check(slot);
        return dir.resolve(slot);
    }

    Path jar(String slot) {
        return slotDir(slot).resolve(JAR_NAME);
    }

    boolean hasJar(String slot) {
        return Files.isRegularFile(jar(slot));
    }

    /** Соседний слот: тот, куда обновление кладёт новую версию, пока работает этот. */
    static String other(String slot) {
        check(slot);
        return SLOTS.get(1 - SLOTS.indexOf(slot));
    }

    SlotState state() throws IOException {
        return SlotState.load(stateFile());
    }

    /**
     * Берёт блокировку владельца слотов. Владелец — единственный процесс, который меняет состояние слотов и
     * считает пробные запуски; остальные (второй экземпляр, воркеры) только читают.
     */
    boolean acquireOwnerLock() {
        if (ownerLock != null) {
            return true;
        }
        Path lockFile = dir.resolve("owner.lock");
        if (!HELD.add(lockFile)) {
            return false;
        }
        FileChannel channel = null;
        try {
            Files.createDirectories(dir);
            channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            if (channel.tryLock() != null) {
                ownerLock = channel;
                return true;
            }
        } catch (OverlappingFileLockException e) {
            // another channel of this process holds it: a second owner is not needed
        } catch (IOException e) {
            System.err.println("Slot owner lock is unavailable, slots are read-only: " + e);
        }
        close(channel);
        HELD.remove(lockFile);
        return false;
    }

    /** Отпускает блокировку владельца (в продовом пути она живёт до конца процесса; нужна тестам). */
    void releaseOwnerLock() {
        if (ownerLock != null) {
            close(ownerLock);
            HELD.remove(dir.resolve("owner.lock"));
        }
        ownerLock = null;
    }

    /**
     * Пишет файл целиком: временный файл рядом, {@code fsync}, атомарное переименование. Оборванная запись (сбой,
     * потеря питания) оставляет прежний файл, а не пустой; временный файл при сбое удаляется.
     */
    static void writeAtomically(Path file, byte[] content) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(content);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
    }

    static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Files.readAllBytes(file));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static void check(String slot) {
        if (!SLOTS.contains(slot)) {
            throw new IllegalArgumentException("Unknown slot: " + slot);
        }
    }

    private static void close(FileChannel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException e) {
            System.err.println("Slot lock channel was not closed: " + e);
        }
    }
}
