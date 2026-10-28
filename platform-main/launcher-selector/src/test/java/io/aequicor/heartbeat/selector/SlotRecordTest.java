package io.aequicor.heartbeat.selector;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SlotRecordTest {
    static final String SHA = "0123456789abcdef".repeat(4);
    static final String SIGNATURE = "A".repeat(86) + "==";

    static SlotRecord record() {
        return new SlotRecord("launcher", "t1a2b3c4d5e6f", SHA, "launcher", 7, SIGNATURE);
    }

    /** Подписываемые байты — контракт с подписывающим в build-logic: тот же вектор проверяет и он. */
    @Test
    void canonicalBytesAreFixed() {
        String expected = "heartbeat-launcher-record-v1\n"
            + "id=launcher\n"
            + "version=t1a2b3c4d5e6f\n"
            + "sha256=" + SHA + "\n"
            + "kind=launcher\n"
            + "launcherApi=7\n";
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), record().canonical());
    }

    @Test
    void signatureIsNotPartOfTheSignedBytes() {
        SlotRecord other = new SlotRecord("launcher", "t1a2b3c4d5e6f", SHA, "launcher", 7, "B".repeat(86) + "==");
        assertArrayEquals(record().canonical(), other.canonical());
    }

    @Test
    void everyFieldIsPartOfTheSignedBytes() {
        byte[] base = record().canonical();
        SlotRecord[] changed = {
            new SlotRecord("other", "t1a2b3c4d5e6f", SHA, "launcher", 7, SIGNATURE),
            new SlotRecord("launcher", "t2", SHA, "launcher", 7, SIGNATURE),
            new SlotRecord("launcher", "t1a2b3c4d5e6f", "f".repeat(64), "launcher", 7, SIGNATURE),
            new SlotRecord("launcher", "t1a2b3c4d5e6f", SHA, "base", 7, SIGNATURE),
            new SlotRecord("launcher", "t1a2b3c4d5e6f", SHA, "launcher", 8, SIGNATURE),
        };
        for (SlotRecord other : changed) {
            org.junit.jupiter.api.Assertions.assertFalse(java.util.Arrays.equals(base, other.canonical()), other.toString());
        }
    }

    @Test
    void fieldsThatCouldInjectLinesAreRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new SlotRecord("a\nkind=base", "v", SHA, "launcher", 1, SIGNATURE));
        assertThrows(IllegalArgumentException.class, () -> new SlotRecord("a", "v 1", SHA, "launcher", 1, SIGNATURE));
        assertThrows(IllegalArgumentException.class, () -> new SlotRecord("a", "v", "ABC", "launcher", 1, SIGNATURE));
        assertThrows(IllegalArgumentException.class, () -> new SlotRecord("a", "v", SHA, "launcher", -1, SIGNATURE));
        assertThrows(IllegalArgumentException.class, () -> new SlotRecord("a", "v", SHA, "launcher", 1, "short"));
    }

    @Test
    void roundTripsThroughTheFile(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("slot").resolve(SlotRecord.FILE_NAME);
        record().write(file);
        assertEquals(record(), SlotRecord.read(file));
    }

    @Test
    void unreadableRecordIsAnIoError(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve(SlotRecord.FILE_NAME);
        Files.writeString(file, "id=launcher\nlauncherApi=abc\n", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> SlotRecord.read(file));
        Files.writeString(file, "id=launcher\n", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> SlotRecord.read(file));
    }
}
