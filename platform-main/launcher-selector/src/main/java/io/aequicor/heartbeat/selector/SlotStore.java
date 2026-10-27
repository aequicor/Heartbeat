package io.aequicor.heartbeat.selector;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Раскладка слотов на диске: {@code slots/A/launcher.jar}, {@code slots/B/launcher.jar}, {@code slots/state.properties}
 * и {@code slots/owner.lock} — блокировка владельца слотов на всё время процесса.
 */
final class SlotStore {
    /** Имена слотов; соседний слот — другой элемент списка. */
    static final List<String> SLOTS = List.of("A", "B");
    static final String JAR_NAME = "launcher.jar";

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
        FileChannel channel = null;
        try {
            Files.createDirectories(dir);
            channel = FileChannel.open(dir.resolve("owner.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            if (channel.tryLock() != null) {
                ownerLock = channel;
                return true;
            }
        } catch (OverlappingFileLockException e) {
            // этот процесс уже держит блокировку через другой экземпляр хранилища: второй владелец не нужен
        } catch (IOException e) {
            System.err.println("Slot owner lock is unavailable, slots are read-only: " + e);
        }
        close(channel);
        return false;
    }

    /** Отпускает блокировку владельца (в продовом пути она живёт до конца процесса; нужна тестам). */
    void releaseOwnerLock() {
        close(ownerLock);
        ownerLock = null;
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
