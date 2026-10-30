package io.aequicor.heartbeat.selector;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EmbeddedHostTest {
    @Test
    void removesOnlyTheOwnedHandoffTree(@TempDir Path root) throws Exception {
        Path handoff = Files.createDirectories(root.resolve("handoff/owned"));
        Files.writeString(handoff.resolve("ready"), "ready");
        Path sibling = Files.writeString(root.resolve("keep"), "other launch");
        EmbeddedHost.cleanupHandoff(handoff);
        assertFalse(Files.exists(handoff));
        assertTrue(Files.exists(sibling));
        EmbeddedHost.cleanupHandoff(handoff);
    }

    @Test
    void ordinaryJavaLauncherDoesNotAdvertiseNativeHost() {
        assertEquals(0, EmbeddedHost.version());
    }
}
