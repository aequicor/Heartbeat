package io.aequicor.heartbeat.launcher;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.channels.FileChannel;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LauncherMainTest {
    static String[] received;

    @SuppressWarnings("unused")
    public static void main(String[] args) {
        received = args;
    }

    @Test
    void invokesMainOfTheApplicationClass() throws Exception {
        LauncherMain.invokeMain(getClass().getClassLoader(), LauncherMainTest.class.getName(), new String[] {"a", "b"});
        assertArrayEquals(new String[] {"a", "b"}, received);
    }

    @Test
    void unknownClassFails() {
        assertThrows(ClassNotFoundException.class,
                () -> LauncherMain.invokeMain(getClass().getClassLoader(), "no.such.Main", new String[0]));
    }

    @Test
    void secondInstanceDoesNotGetTheLock(@TempDir Path root) throws Exception {
        try (FileChannel first = LauncherMain.acquireInstanceLock(root)) {
            assertNotNull(first);
            assertNull(LauncherMain.acquireInstanceLock(root));
        }
        try (FileChannel again = LauncherMain.acquireInstanceLock(root)) {
            assertNotNull(again);
        }
    }

    @Test
    void workerArgumentsAreAuxiliary() {
        assertTrue(LauncherMain.isAuxiliary(new String[] {"--heartbeat-plantuml-worker"}));
        assertTrue(LauncherMain.isAuxiliary(new String[] {"--verify-window-runtime"}));
        assertFalse(LauncherMain.isAuxiliary(new String[0]));
        assertFalse(LauncherMain.isAuxiliary(new String[] {"/Users/me/file.md"}));
    }

    @Test
    void cleanExitNeedsReturnOrAppFlag() {
        assertTrue(LauncherMain.isCleanExit(true));
        assertFalse(LauncherMain.isCleanExit(false));
        System.setProperty(LauncherMain.EXIT_CLEAN, "true");
        try {
            assertTrue(LauncherMain.isCleanExit(false));
        } finally {
            System.clearProperty(LauncherMain.EXIT_CLEAN);
        }
    }
}
