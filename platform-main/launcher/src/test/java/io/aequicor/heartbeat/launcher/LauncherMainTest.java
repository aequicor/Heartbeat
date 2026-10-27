package io.aequicor.heartbeat.launcher;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
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
    void userDataDirFollowsTheOsConventions() {
        assertEquals(Path.of("/Users/me/Library/Application Support/Heartbeat"),
            LauncherMain.userDataDir("Mac OS X", "/Users/me", null, null, null));
        assertEquals(Path.of("C:\\Users\\me\\AppData\\Roaming").resolve("Aequicor").resolve("Heartbeat"),
            LauncherMain.userDataDir("Windows 11", "C:\\Users\\me", "C:\\Users\\me\\AppData\\Roaming", null, null));
        assertEquals(Path.of("/home/me/.local/share/heartbeat"),
            LauncherMain.userDataDir("Linux", "/home/me", null, null, null));
        assertEquals(Path.of("/data/heartbeat-dev"),
            LauncherMain.userDataDir("Linux", "/home/me", null, "/data", "Heartbeat-Dev"));
        assertEquals(Path.of("/home/me/.local/share/heartbeat"),
            LauncherMain.userDataDir("Linux", "/home/me", null, "relative", "bad name!"));
    }

    @Test
    void launcherRootLiesBesideTheDataDirectory() {
        Path data = Path.of("/Users/me/Library/Application Support/Heartbeat");
        Path root = LauncherMain.launcherRootBeside(data);
        assertEquals(Path.of("/Users/me/Library/Application Support/Heartbeat-launcher"), root);
        assertFalse(root.startsWith(data));
        assertFalse(data.startsWith(root));
    }

    @Test
    void withoutTheAgentTheTrainGetsItsOwnLoader() throws Exception {
        assertFalse(LauncherAgent.isAvailable());
        ClassLoader loader = LauncherMain.trainLoader(java.util.List.of());
        assertTrue(loader instanceof java.net.URLClassLoader);
        assertThrows(IllegalStateException.class, () -> LauncherAgent.appendToSystemClassLoader(java.util.List.of()));
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

    @Test
    void unavailableInstanceLockFallsBackToReadOnly(@TempDir Path tmp) throws Exception {
        Path notADirectory = Files.writeString(tmp.resolve("root"), "");
        assertNull(LauncherMain.acquireInstanceLock(notADirectory));
    }

    @Test
    void failedBundleReportKeepsTheApplicationFailure(@TempDir Path tmp) throws Exception {
        Path notADirectory = Files.writeString(tmp.resolve("root"), "");
        Launcher launcher = new Launcher(notADirectory, tmp.resolve("data"));
        InvocationTargetException failure = new InvocationTargetException(new IllegalStateException("app"));
        LauncherMain.reportBundleFailure(launcher, failure);
        assertEquals(1, failure.getSuppressed().length);
        assertTrue(failure.getSuppressed()[0] instanceof IOException);
    }
}
