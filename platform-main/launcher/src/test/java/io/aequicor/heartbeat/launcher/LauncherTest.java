package io.aequicor.heartbeat.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LauncherTest {
    @TempDir
    Path tmp;

    Path root;
    Path data;
    Launcher launcher;
    final Map<String, byte[]> remote = new HashMap<>();
    int fetches;

    @BeforeEach
    void setUp() throws IOException {
        root = tmp.resolve("launcher");
        data = tmp.resolve("data");
        Files.createDirectories(data);
        launcher = new Launcher(root, data);
    }

    String sha(byte[] bytes) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    String manifest(String train, int api, int schema, String... jars) throws Exception {
        StringBuilder bundles = new StringBuilder();
        for (String jar : jars) {
            byte[] content = (jar + "@" + train).getBytes(StandardCharsets.UTF_8);
            remote.put(jar + "@" + train, content);
            if (bundles.length() > 0) {
                bundles.append(',');
            }
            bundles.append("{\"id\":\"").append(jar).append("\",\"version\":\"1\",\"jar\":\"").append(jar)
                .append(".jar\",\"sha256\":\"").append(sha(content)).append("\"}");
        }
        return "{\"train\":\"" + train + "\",\"platformApi\":" + api + ",\"dbSchema\":" + schema
            + ",\"bundles\":[" + bundles + "]}";
    }

    TrainStore.Fetcher fetcher(String train) {
        return bundle -> {
            fetches++;
            byte[] data = remote.get(bundle.id() + "@" + train);
            return new ByteArrayInputStream(data);
        };
    }

    void install(String train, int schema, String... jars) throws Exception {
        launcher.applyUpdate(manifest(train, 1, schema, jars), fetcher(train));
    }

    @Test
    void parsesManifestFields() {
        TrainManifest m = TrainManifest.parse("{\"train\":\"t1\",\"platformApi\":2,\"bundles\":["
            + "{\"id\":\"web\",\"version\":\"1.0\",\"jar\":\"web.jar\",\"sha256\":\"" + "a".repeat(64) + "\"}]}");
        assertEquals("t1", m.train());
        assertEquals(2, m.platformApi());
        assertEquals(0, m.dbSchema());
        assertEquals("web", m.bundles().get(0).id());
    }

    @Test
    void rejectsPathTraversalInJarName() {
        assertThrows(IllegalArgumentException.class, () -> TrainManifest.parse("{\"train\":\"t\",\"platformApi\":1,"
            + "\"bundles\":[{\"id\":\"x\",\"version\":\"1\",\"jar\":\"../x.jar\",\"sha256\":\"" + "a".repeat(64) + "\"}]}"));
    }

    @Test
    void prepareWithoutTrainUsesSeed() throws Exception {
        Launcher.Prepared prepared = launcher.prepare();
        assertNull(prepared.train());
        assertTrue(prepared.classpath().isEmpty());
    }

    @Test
    void installedTrainBuildsClasspathInManifestOrder() throws Exception {
        install("t1", 0, "foundation", "web");
        Launcher.Prepared prepared = launcher.prepare();
        assertEquals("t1", prepared.train());
        assertEquals(2, prepared.classpath().size());
        assertEquals("foundation@t1", Files.readString(prepared.classpath().get(0)));
        assertEquals("web@t1", Files.readString(prepared.classpath().get(1)));
    }

    @Test
    void hashMismatchLeavesNoTrainAndNoJar() throws Exception {
        String m = manifest("t1", 1, 0, "web");
        remote.put("web@t1", "tampered".getBytes(StandardCharsets.UTF_8));
        assertThrows(TrainStore.HashMismatchException.class, () -> launcher.applyUpdate(m, fetcher("t1")));
        assertNull(launcher.prepare().train());
        assertFalse(Files.exists(root.resolve("trains").resolve("t1")));
        try (var files = Files.list(root.resolve("store"))) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void unchangedJarsAreNotDownloadedAgain() throws Exception {
        install("t1", 0, "foundation");
        int before = fetches;
        // t2 содержит тот же jar foundation с тем же содержимым.
        remote.put("foundation@t2", remote.get("foundation@t1"));
        String m2 = "{\"train\":\"t2\",\"platformApi\":1,\"bundles\":[{\"id\":\"foundation\",\"version\":\"1\","
            + "\"jar\":\"foundation.jar\",\"sha256\":\"" + sha(remote.get("foundation@t1")) + "\"}]}";
        launcher.applyUpdate(m2, fetcher("t2"));
        assertEquals(before, fetches);
    }

    @Test
    void newerPlatformApiIsRejected() throws Exception {
        String m = manifest("t1", 99, 0, "web");
        assertThrows(Launcher.IncompatiblePlatformException.class, () -> launcher.applyUpdate(m, fetcher("t1")));
        assertNull(launcher.prepare().train());
    }

    @Test
    void keepsOnlyTwoTrains() throws Exception {
        install("t1", 0, "a");
        install("t2", 0, "a");
        install("t3", 0, "a");
        assertFalse(Files.exists(root.resolve("trains").resolve("t1")));
        assertTrue(Files.exists(root.resolve("trains").resolve("t2")));
        assertTrue(Files.exists(root.resolve("trains").resolve("t3")));
        assertFalse(Files.exists(launcherJar("a@t1")));
        assertTrue(Files.exists(launcherJar("a@t2")));
    }

    Path launcherJar(String content) throws Exception {
        return root.resolve("store").resolve(sha(content.getBytes(StandardCharsets.UTF_8)) + ".jar");
    }

    @Test
    void singleCrashWithinWindowDoesNotRollBack() throws Exception {
        install("t1", 0, "a");
        install("t2", 0, "a");
        launcher.prepare();
        // процесс упал: markCleanExit/markStable не вызваны
        Launcher.Prepared second = launcher.prepare();
        assertEquals("t2", second.train());
        assertNull(second.rolledBackFrom());
    }

    @Test
    void twoCrashesWithinWindowRollBackWholeTrain() throws Exception {
        install("t1", 0, "a");
        install("t2", 0, "a");
        launcher.prepare();
        launcher.prepare(); // 1-й краш
        Launcher.Prepared third = launcher.prepare(); // 2-й краш
        assertEquals("t1", third.train());
        assertEquals("t2", third.rolledBackFrom());
        assertTrue(launcher.isBad("t2"));
    }

    @Test
    void stableProcessResetsCrashCounter() throws Exception {
        install("t1", 0, "a");
        install("t2", 0, "a");
        launcher.prepare();
        launcher.prepare(); // 1-й краш
        launcher.markStable();
        launcher.prepare(); // краш после окна стабильности не считается
        Launcher.Prepared next = launcher.prepare();
        assertEquals("t2", next.train());
        assertFalse(launcher.isBad("t2"));
    }

    @Test
    void cleanExitIsNotACrash() throws Exception {
        install("t1", 0, "a");
        install("t2", 0, "a");
        for (int i = 0; i < 4; i++) {
            assertEquals("t2", launcher.prepare().train());
            launcher.markCleanExit();
        }
        assertFalse(launcher.isBad("t2"));
    }

    @Test
    void bundleInitFailureRollsBackImmediately() throws Exception {
        install("t1", 0, "a");
        install("t2", 0, "a");
        launcher.prepare();
        assertEquals("t1", launcher.reportBundleFailure());
        assertTrue(launcher.isBad("t2"));
        assertEquals("t1", launcher.prepare().train());
    }

    @Test
    void badTrainCannotBeActivatedAgain() throws Exception {
        install("t1", 0, "a");
        install("t2", 0, "a");
        launcher.reportBundleFailure();
        assertThrows(IOException.class, () -> install("t2", 0, "a"));
    }

    @Test
    void corruptedJarOnDiskTriggersRollbackOnPrepare() throws Exception {
        install("t1", 0, "a");
        install("t2", 0, "a");
        Files.writeString(launcherJar("a@t2"), "corrupted");
        Launcher.Prepared prepared = launcher.prepare();
        assertEquals("t1", prepared.train());
        assertEquals("t2", prepared.rolledBackFrom());
    }

    @Test
    void rollbackRestoresDbBackupMadeBeforeMigration() throws Exception {
        install("t1", 1, "a");
        Files.writeString(data.resolve("app.db"), "v1");
        install("t2", 2, "a"); // схема выросла → бэкап
        Files.writeString(data.resolve("app.db"), "v2-migrated");
        Files.writeString(data.resolve("extra.db"), "new");
        launcher.prepare();
        assertEquals("t1", launcher.reportBundleFailure());
        assertEquals("v1", Files.readString(data.resolve("app.db")));
        assertFalse(Files.exists(data.resolve("extra.db")));
    }

    @Test
    void noBackupWhenSchemaUnchanged() throws Exception {
        install("t1", 1, "a");
        Files.writeString(data.resolve("app.db"), "v1");
        install("t2", 1, "a");
        assertFalse(Files.exists(root.resolve("backups").resolve("t2")));
        Files.writeString(data.resolve("app.db"), "changed");
        launcher.reportBundleFailure();
        assertEquals("changed", Files.readString(data.resolve("app.db")));
    }

    @Test
    void crashRollbackAlsoRestoresDb() throws Exception {
        install("t1", 1, "a");
        Files.writeString(data.resolve("app.db"), "v1");
        install("t2", 2, "a");
        Files.writeString(data.resolve("app.db"), "v2");
        launcher.prepare();
        launcher.prepare();
        Launcher.Prepared prepared = launcher.prepare();
        assertTrue(prepared.dbRestored());
        assertEquals("v1", Files.readString(data.resolve("app.db")));
    }

    @Test
    void stableTrainDiscardsBackup() throws Exception {
        install("t1", 1, "a");
        install("t2", 2, "a");
        launcher.prepare();
        launcher.markStable();
        assertFalse(Files.exists(root.resolve("backups").resolve("t2")));
    }

    @Test
    void rollbackWithoutPreviousFallsBackToSeed() throws Exception {
        install("t1", 0, "a");
        assertNull(launcher.reportBundleFailure());
        assertNull(launcher.prepare().train());
        assertTrue(launcher.isBad("t1"));
    }

    @Test
    void classLoaderLoadsFromTrainJars() throws Exception {
        install("t1", 0, "a");
        List<Path> cp = launcher.prepare().classpath();
        try (var loader = Launcher.newClassLoader(cp, null)) {
            assertEquals(1, loader.getURLs().length);
        }
    }
}
