package io.aequicor.heartbeat.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    void bundleInitFailureRollsBackAtNextStart() throws Exception {
        install("t1", 0, "a");
        install("t2", 0, "a");
        launcher.prepare();
        assertEquals("t1", launcher.reportBundleFailure());
        assertFalse(launcher.isBad("t2"));
        Launcher.Prepared next = launcher.prepare();
        assertEquals("t1", next.train());
        assertEquals("t2", next.rolledBackFrom());
        assertTrue(launcher.isBad("t2"));
    }

    @Test
    void badTrainCannotBeActivatedAgain() throws Exception {
        install("t1", 0, "a");
        install("t2", 0, "a");
        launcher.reportBundleFailure();
        launcher.prepare();
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
        // Данные открыты работающим процессом: восстановление — только при следующем запуске.
        assertEquals("v2-migrated", Files.readString(data.resolve("app.db")));
        Launcher.Prepared next = launcher.prepare();
        assertTrue(next.dbRestored());
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
        assertEquals("t1", launcher.prepare().train());
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
    void bundleFailureWithoutPreviousKeepsTheOnlyTrain() throws Exception {
        install("t1", 0, "a");
        launcher.prepare();
        assertNull(launcher.reportBundleFailure());
        assertEquals("t1", launcher.prepare().train());
        assertFalse(launcher.isBad("t1"));
    }

    @Test
    void crashLimitWithoutPreviousKeepsTheOnlyTrain() throws Exception {
        install("t1", 0, "a");
        for (int i = 0; i < Launcher.CRASH_LIMIT + 2; i++) {
            assertEquals("t1", launcher.prepare().train());
        }
        assertFalse(launcher.isBad("t1"));
    }

    @Test
    void bundleFailureAfterStableIsIgnored() throws Exception {
        install("t1", 0, "a");
        install("t2", 0, "a");
        launcher.prepare();
        launcher.markStable();
        assertNull(launcher.reportBundleFailure());
        assertEquals("t2", launcher.prepare().train());
        assertFalse(launcher.isBad("t2"));
    }

    @Test
    void requestedRollbackKeepsBackupWhenStableTimerFires() throws Exception {
        install("t1", 1, "a");
        install("t2", 2, "a");
        launcher.prepare();
        launcher.reportBundleFailure();
        launcher.markStable();
        assertTrue(Files.exists(root.resolve("backups").resolve("t2")));
        assertTrue(launcher.prepare().dbRestored());
    }

    @Test
    void activationBindsBackupInTheSameState() throws Exception {
        install("t1", 1, "a");
        install("t2", 2, "a");
        LauncherState state = launcher.store().state();
        assertEquals("t2", state.current);
        assertEquals("t2", state.backupOf);
        assertEquals(2, state.dbSchema);
    }

    @Test
    void corruptJarOfMigratedTrainWithoutBackupRequiresReinstall() throws Exception {
        install("t1", 1, "a");
        install("t2", 2, "a");
        launcher.prepare();
        launcher.markStable(); // бэкап удалён, БД уже на схеме 2
        Files.writeString(launcherJar("a@t2"), "corrupted");
        IOException error = assertThrows(IOException.class, () -> launcher.prepare());
        assertTrue(error.getMessage().contains("reinstall"));
        assertEquals("t2", launcher.store().state().current);
        assertFalse(launcher.isBad("t2"));
    }

    @Test
    void corruptJarOfMigratedTrainRestoresBackupOnRollback() throws Exception {
        install("t1", 1, "a");
        Files.writeString(data.resolve("app.db"), "v1");
        install("t2", 2, "a");
        Files.writeString(data.resolve("app.db"), "v2");
        Files.writeString(launcherJar("a@t2"), "corrupted");
        Launcher.Prepared prepared = launcher.prepare();
        assertEquals("t1", prepared.train());
        assertTrue(prepared.dbRestored());
        assertEquals("v1", Files.readString(data.resolve("app.db")));
        assertEquals(1, launcher.store().state().dbSchema);
    }

    @Test
    void rollbackToLowerSchemaWithoutBackupIsRefused() throws Exception {
        install("t1", 1, "a");
        install("t2", 2, "a");
        launcher.prepare();
        TrainStore.deleteRecursively(root.resolve("backups").resolve("t2"));
        launcher.reportBundleFailure();
        Launcher.Prepared next = launcher.prepare();
        assertEquals("t2", next.train());
        assertNull(next.rolledBackFrom());
        assertFalse(launcher.isBad("t2"));
    }

    @Test
    void corruptJarWithoutPreviousRequiresReinstall() throws Exception {
        install("t1", 0, "a");
        Files.writeString(launcherJar("a@t1"), "corrupted");
        assertThrows(IOException.class, () -> launcher.prepare());
        assertEquals("t1", launcher.store().state().current);
    }

    void queuePending(String train, String json, long mtime) throws Exception {
        Path dir = Files.createDirectories(root.resolve("pending").resolve(train));
        Files.writeString(dir.resolve("manifest.json"), json);
        Files.setLastModifiedTime(dir, java.nio.file.attribute.FileTime.fromMillis(mtime));
    }

    /** Кладёт jar поезда в store, как это делает app-update. */
    void storeJars(String train, String... jars) throws Exception {
        Files.createDirectories(root.resolve("store"));
        for (String jar : jars) {
            Files.write(launcherJar(jar + "@" + train), remote.get(jar + "@" + train));
        }
    }

    @Test
    void pendingTrainIsActivatedAtPrepare() throws Exception {
        install("t1", 0, "a");
        String m2 = manifest("t2", 1, 0, "a", "b");
        storeJars("t2", "a", "b");
        queuePending("t2", m2, 1_000);
        int before = fetches;
        Launcher.Prepared prepared = launcher.prepare();
        assertEquals("t2", prepared.train());
        assertEquals(before, fetches);
        assertEquals("t1", launcher.store().state().previous);
        assertFalse(Files.exists(root.resolve("pending").resolve("t2")));
    }

    @Test
    void pendingTrainWithMissingJarIsDroppedAndCurrentKept() throws Exception {
        install("t1", 0, "a");
        String m2 = manifest("t2", 1, 0, "a", "b");
        storeJars("t2", "a");
        queuePending("t2", m2, 1_000);
        assertEquals("t1", launcher.prepare().train());
        assertFalse(Files.exists(root.resolve("pending").resolve("t2")));
        assertFalse(Files.exists(root.resolve("trains").resolve("t2")));
    }

    @Test
    void newestPendingTrainWinsAndOthersAreRemoved() throws Exception {
        install("t1", 0, "a");
        String m2 = manifest("t2", 1, 0, "a");
        String m3 = manifest("t3", 1, 0, "a");
        storeJars("t2", "a");
        queuePending("t2", m2, 1_000);
        storeJars("t3", "a");
        queuePending("t3", m3, 2_000);
        assertEquals("t3", launcher.prepare().train());
        assertFalse(Files.exists(root.resolve("pending").resolve("t2")));
        assertFalse(Files.exists(root.resolve("pending").resolve("t3")));
    }

    @Test
    void badOrIncompatiblePendingTrainIsSkipped() throws Exception {
        install("t1", 0, "a");
        install("t2", 0, "a");
        launcher.reportBundleFailure();
        launcher.prepare(); // t2 плохой
        storeJars("t2", "a");
        queuePending("t2", manifest("t2", 1, 0, "a"), 1_000);
        assertEquals("t1", launcher.prepare().train());
        String m9 = manifest("t9", 99, 0, "a");
        storeJars("t9", "a");
        queuePending("t9", m9, 1_000);
        assertEquals("t1", launcher.prepare().train());
        assertFalse(Files.exists(root.resolve("pending").resolve("t9")));
    }

    @Test
    void pruneKeepsJarsOfPendingTrain() throws Exception {
        install("t1", 0, "a");
        String m5 = manifest("t5", 1, 0, "z");
        storeJars("t5", "z");
        queuePending("t5", m5, 1_000);
        install("t2", 0, "a"); // активация чистит хранилище
        assertTrue(Files.exists(launcherJar("z@t5")));
    }

    @Test
    void pruneRemovesOrphanBackups() throws Exception {
        Files.createDirectories(root.resolve("backups").resolve("old"));
        install("t1", 0, "a");
        assertFalse(Files.exists(root.resolve("backups").resolve("old")));
    }

    @Test
    void stateMutationsAreSerializedAcrossStores() throws Exception {
        install("t1", 0, "a");
        int threads = 4;
        int rounds = 25;
        List<Thread> workers = new java.util.ArrayList<>();
        java.util.concurrent.ConcurrentLinkedQueue<Throwable> errors = new java.util.concurrent.ConcurrentLinkedQueue<>();
        for (int t = 0; t < threads; t++) {
            TrainStore own = new TrainStore(root);
            workers.add(new Thread(() -> {
                try {
                    for (int i = 0; i < rounds; i++) {
                        own.locked(() -> {
                            LauncherState state = own.state();
                            state.crashes++;
                            state.save(own.stateFile());
                            return null;
                        });
                    }
                } catch (Throwable e) {
                    errors.add(e);
                }
            }));
        }
        workers.forEach(Thread::start);
        for (Thread worker : workers) {
            worker.join(30_000);
        }
        assertTrue(errors.isEmpty(), errors.toString());
        assertEquals(threads * rounds, launcher.store().state().crashes);
    }

    @Test
    void readOnlyPrepareDoesNotTouchState() throws Exception {
        install("t1", 0, "a");
        install("t2", 0, "a");
        launcher.prepare();
        byte[] before = Files.readAllBytes(root.resolve("state.properties"));
        for (int i = 0; i < 3; i++) {
            assertEquals("t2", launcher.prepareReadOnly().train());
        }
        assertEquals(new String(before, StandardCharsets.UTF_8),
            new String(Files.readAllBytes(root.resolve("state.properties")), StandardCharsets.UTF_8));
    }

    @Test
    void exposesBadTrainsToTheApp() throws Exception {
        install("t1", 0, "a");
        install("t2", 0, "a");
        launcher.reportBundleFailure();
        Launcher.Prepared prepared = launcher.prepare();
        try {
            launcher.exposeToApp(prepared, root);
            assertEquals("t2", System.getProperty("heartbeat.train.bad"));
            assertEquals("t1", System.getProperty("heartbeat.train"));
            new Launcher(tmp.resolve("other"), data).exposeToApp(prepared, tmp.resolve("other"));
            assertNull(System.getProperty("heartbeat.train.bad"));
        } finally {
            System.clearProperty("heartbeat.train.bad");
            System.clearProperty("heartbeat.train");
            System.clearProperty("heartbeat.launcher.root");
        }
    }

    @Test
    void corruptStateDoesNotCrash() throws Exception {
        Files.createDirectories(root);
        Files.writeString(root.resolve("state.properties"), "crashes=abc\ndbSchema=x\n");
        LauncherState state = launcher.store().state();
        assertEquals(0, state.crashes);
        assertEquals(0, state.dbSchema);
        Files.writeString(root.resolve("state.properties"), "current=\\u12\n");
        assertEquals("", launcher.store().state().current);
    }

    @Test
    void parsesOptionalBundleUrl() {
        String sha = "a".repeat(64);
        TrainManifest m = TrainManifest.parse("{\"train\":\"t1\",\"platformApi\":1,\"bundles\":["
            + "{\"id\":\"web\",\"version\":\"1\",\"jar\":\"web.jar\",\"sha256\":\"" + sha + "\","
            + "\"url\":\"https://example.com/v1/web.jar\"},"
            + "{\"id\":\"core\",\"version\":\"1\",\"jar\":\"core.jar\",\"sha256\":\"" + sha + "\"}]}");
        assertEquals("https://example.com/v1/web.jar", m.bundles().get(0).url());
        assertNull(m.bundles().get(1).url());
        assertThrows(IllegalArgumentException.class, () -> TrainManifest.parse("{\"train\":\"t1\",\"platformApi\":1,"
            + "\"bundles\":[{\"id\":\"web\",\"version\":\"1\",\"jar\":\"web.jar\",\"sha256\":\"" + sha + "\","
            + "\"url\":\"http://example.com/web.jar\"}]}"));
    }

    @Test
    void rejectsOutOfRangeNumbers() {
        assertThrows(IllegalArgumentException.class, () -> TrainManifest.parse(
            "{\"train\":\"t\",\"platformApi\":4294967297,\"bundles\":[]}"));
        assertThrows(IllegalArgumentException.class, () -> TrainManifest.parse(
            "{\"train\":\"t\",\"platformApi\":1,\"dbSchema\":-1,\"bundles\":[]}"));
    }

    @Test
    void classLoaderLoadsFromTrainJars() throws Exception {
        install("t1", 0, "a");
        List<Path> cp = launcher.prepare().classpath();
        try (var loader = Launcher.newClassLoader(cp, null)) {
            assertEquals(1, loader.getURLs().length);
        }
    }

    /** Каталог без права записи: удалить его содержимое нельзя (как jar, открытый воркером на Windows). */
    void withReadOnly(Path dir, ThrowingRunnable body) throws Exception {
        assumeTrue(Files.getFileStore(dir).supportsFileAttributeView("posix"));
        // root обходит права доступа: каталог остался бы записываемым
        assumeFalse("root".equals(System.getProperty("user.name")));
        Set<PosixFilePermission> before = Files.getPosixFilePermissions(dir);
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            body.run();
        } finally {
            Files.setPosixFilePermissions(dir, before);
        }
    }

    interface ThrowingRunnable {
        void run() throws Exception;
    }

    @Test
    void pruneFailureDoesNotFailActivation() throws Exception {
        install("t1", 0, "a");
        install("t2", 0, "a");
        withReadOnly(root.resolve("trains").resolve("t1"), () -> {
            install("t3", 0, "a");
            assertEquals("t3", launcher.store().state().current);
            assertEquals("t2", launcher.store().state().previous);
            // Очистка продолжилась после сбоя: jar старого поезда удалён.
            assertFalse(Files.exists(launcherJar("a@t1")));
        });
        launcher.store().prune();
        assertFalse(Files.exists(root.resolve("trains").resolve("t1")));
    }

    @Test
    void pruneFailureDuringRollbackStillRestoresAndDiscardsBackup() throws Exception {
        install("t1", 1, "a");
        Files.writeString(data.resolve("app.db"), "v1");
        install("t2", 2, "a");
        Files.writeString(data.resolve("app.db"), "v2");
        launcher.prepare();
        launcher.reportBundleFailure();
        withReadOnly(root.resolve("trains").resolve("t2"), () -> {
            Launcher.Prepared next = launcher.prepare();
            assertEquals("t1", next.train());
            assertTrue(next.dbRestored());
        });
        assertEquals("v1", Files.readString(data.resolve("app.db")));
        assertFalse(Files.exists(root.resolve("backups").resolve("t2")));
        assertEquals("", launcher.store().state().backupOf);
        assertTrue(launcher.isBad("t2"));
    }

    @Test
    void restoreIsDeferredWhileStorageIsLeased() throws Exception {
        Path lockFile = Files.createDirectories(data.resolve(DbBackup.OWNER_DIR)).resolve(DbBackup.OWNER_LOCK);
        Files.writeString(lockFile, "");
        install("t1", 1, "a");
        Files.writeString(data.resolve("app.db"), "v1");
        install("t2", 2, "a");
        assertFalse(Files.exists(root.resolve("backups").resolve("t2").resolve(DbBackup.OWNER_DIR)));
        Files.writeString(data.resolve("app.db"), "v2");
        launcher.prepare();
        assertEquals("t1", launcher.reportBundleFailure());
        String m3 = manifest("t3", 1, 2, "a");
        storeJars("t3", "a");
        queuePending("t3", m3, 1_000);
        try (FileChannel worker = FileChannel.open(lockFile, StandardOpenOption.WRITE);
             FileLock ignored = worker.lock()) {
            Launcher.Prepared blocked = launcher.prepare();
            assertEquals("t2", blocked.train());
            assertNull(blocked.rolledBackFrom());
            assertEquals("v2", Files.readString(data.resolve("app.db")));
            assertTrue(Files.exists(root.resolve("backups").resolve("t2")));
            assertTrue(launcher.store().state().rollbackRequested);
            assertTrue(Files.exists(root.resolve("pending").resolve("t3")));
            launcher.markStable(); // запрос отката не даёт выбросить бэкап
            assertTrue(Files.exists(root.resolve("backups").resolve("t2")));
        }
        Launcher.Prepared next = launcher.prepare();
        assertEquals("t2", next.rolledBackFrom());
        assertTrue(next.dbRestored());
        assertEquals("v1", Files.readString(data.resolve("app.db")));
        assertTrue(Files.exists(lockFile));
        assertTrue(launcher.isBad("t2"));
        assertFalse(launcher.store().state().rollbackRequested);
    }

    @Test
    void damagedRollbackTargetKeepsDataAndBackup() throws Exception {
        install("t1", 1, "a");
        Files.writeString(data.resolve("app.db"), "v1");
        install("t2", 2, "b");
        Files.writeString(data.resolve("app.db"), "v2");
        Files.writeString(launcherJar("a@t1"), "corrupted");
        launcher.prepare();
        assertNull(launcher.reportBundleFailure());
        for (int i = 0; i < Launcher.CRASH_LIMIT + 1; i++) {
            Launcher.Prepared prepared = launcher.prepare();
            assertEquals("t2", prepared.train());
            assertFalse(prepared.dbRestored());
        }
        assertEquals("v2", Files.readString(data.resolve("app.db")));
        assertTrue(Files.exists(root.resolve("backups").resolve("t2")));
        assertFalse(launcher.isBad("t2"));
    }

    @Test
    void backupSchemaIsRecordedOnActivation() throws Exception {
        install("t1", 1, "a");
        install("t2", 3, "a");
        LauncherState state = launcher.store().state();
        assertEquals(1, state.backupSchema);
        launcher.prepare();
        launcher.markStable();
        assertEquals(-1, launcher.store().state().backupSchema);
    }

    @Test
    void rollbackBelowBackupSchemaIsRefused() throws Exception {
        install("t1", 5, "a");
        install("t2", 1, "a"); // схема данных остаётся 5
        install("t3", 6, "a"); // бэкап данных схемы 5, предыдущий поезд — t2 со схемой 1
        assertEquals(5, launcher.store().state().backupSchema);
        launcher.prepare();
        assertNull(launcher.reportBundleFailure());
        for (int i = 0; i < Launcher.CRASH_LIMIT + 1; i++) {
            assertEquals("t3", launcher.prepare().train());
        }
        assertTrue(Files.exists(root.resolve("backups").resolve("t3")));
        assertFalse(launcher.isBad("t3"));
    }

    @Test
    void incompletePendingDirIsRemoved() throws Exception {
        install("t1", 0, "a");
        Path dir = Files.createDirectories(root.resolve("pending").resolve("t5"));
        Files.writeString(dir.resolve("manifest.json.tmp"), "{");
        assertEquals("t1", launcher.prepare().train());
        assertFalse(Files.exists(dir));
    }

    @Test
    void corruptStoreJarIsDeletedSoItCanBeFetchedAgain() throws Exception {
        String m = manifest("t1", 1, 0, "a");
        launcher.applyUpdate(m, fetcher("t1"));
        Files.writeString(launcherJar("a@t1"), "corrupted");
        assertThrows(IOException.class, () -> launcher.store().stage(m, bundle -> {
            throw new IOException("offline");
        }));
        assertFalse(Files.exists(launcherJar("a@t1")));
        launcher.store().stage(m, fetcher("t1"));
        assertEquals("a@t1", Files.readString(launcherJar("a@t1")));
    }

    @Test
    void restoreFailingMidwayKeepsRollbackRequestAndBackup() throws Exception {
        install("t1", 1, "a");
        Files.writeString(data.resolve("app.db"), "v1");
        install("t2", 2, "a");
        Files.writeString(data.resolve("app.db"), "v2");
        // Каталог без права записи не переносится (нужно обновить его ".."): замена срывается после app.db.
        Path stuck = Files.createDirectories(data.resolve("z-cache"));
        launcher.prepare();
        assertEquals("t1", launcher.reportBundleFailure());
        withReadOnly(stuck, () -> {
            Launcher.Prepared blocked = launcher.prepare();
            assertEquals("t2", blocked.train());
            assertNull(blocked.rolledBackFrom());
            assertEquals("v2", Files.readString(data.resolve("app.db")));
            assertTrue(Files.isDirectory(stuck));
            assertTrue(launcher.store().state().rollbackRequested);
            assertTrue(Files.exists(root.resolve("backups").resolve("t2")));
            launcher.markStable(); // запрос отката не даёт выбросить бэкап
            assertTrue(Files.exists(root.resolve("backups").resolve("t2")));
        });
        Launcher.Prepared next = launcher.prepare();
        assertEquals("t2", next.rolledBackFrom());
        assertTrue(next.dbRestored());
        assertEquals("v1", Files.readString(data.resolve("app.db")));
        assertFalse(Files.exists(stuck));
        assertFalse(launcher.store().state().rollbackRequested);
        assertFalse(Files.exists(root.resolve("backups").resolve("t2")));
        assertTrue(launcher.isBad("t2"));
    }

    @Test
    void restoreDoesNotFollowSymlinksInData() throws Exception {
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Files.writeString(outside.resolve("keep.txt"), "keep");
        install("t1", 1, "a");
        Files.writeString(data.resolve("app.db"), "v1");
        install("t2", 2, "a");
        Path link = data.resolve("link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "symlinks are unavailable: " + e);
        }
        launcher.prepare();
        launcher.reportBundleFailure();
        assertTrue(launcher.prepare().dbRestored());
        assertFalse(Files.exists(link, LinkOption.NOFOLLOW_LINKS));
        assertEquals("keep", Files.readString(outside.resolve("keep.txt")));
    }

    @Test
    void migratingActivationWaitsForStorageLease() throws Exception {
        install("t1", 1, "a");
        Files.writeString(data.resolve("app.db"), "v1");
        launcher.prepare();
        launcher.markStable();
        Path lockFile = data.resolve(DbBackup.OWNER_DIR).resolve(DbBackup.OWNER_LOCK);
        assertTrue(Files.exists(lockFile)); // бэкап создаёт файл аренды и держит её
        String m2 = manifest("t2", 1, 2, "a");
        storeJars("t2", "a");
        queuePending("t2", m2, 1_000);
        try (FileChannel worker = FileChannel.open(lockFile, StandardOpenOption.WRITE);
             FileLock held = worker.lock()) {
            assertThrows(DbBackup.StorageInUseException.class, () -> install("t3", 3, "a"));
            Launcher.Prepared blocked = launcher.prepare();
            assertEquals("t1", blocked.train());
            assertTrue(Files.exists(root.resolve("pending").resolve("t2").resolve("manifest.json")));
            assertFalse(Files.exists(root.resolve("backups").resolve("t2")));
            assertTrue(held.isValid());
        }
        Launcher.Prepared next = launcher.prepare();
        assertEquals("t2", next.train());
        assertFalse(Files.exists(root.resolve("pending").resolve("t2")));
        assertEquals("v1", Files.readString(root.resolve("backups").resolve("t2").resolve("app.db")));
    }

    @Test
    void backupRefusesDataInsideLauncherRoot() throws Exception {
        launcher = new Launcher(root, root.resolve("data"));
        install("t1", 0, "a"); // без миграции бэкап не нужен
        assertThrows(DbBackup.UnsafeLayoutException.class, () -> install("t2", 1, "a"));
        assertEquals("t1", launcher.store().state().current);
        assertFalse(Files.exists(root.resolve("backups").resolve("t2")));
    }

    @Test
    void backupRefusesLauncherRootInsideData() throws Exception {
        Path nested = data.resolve("launcher");
        launcher = new Launcher(nested, data);
        assertThrows(DbBackup.UnsafeLayoutException.class, () -> install("t1", 1, "a"));
        assertEquals("", launcher.store().state().current);
        // Через symlink: реальные пути пересекаются, хотя записанные — нет.
        Path alias = tmp.resolve("alias");
        try {
            Files.createSymbolicLink(alias, data);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "symlinks are unavailable: " + e);
        }
        launcher = new Launcher(alias.resolve("launcher2"), data);
        assertThrows(DbBackup.UnsafeLayoutException.class, () -> install("t1", 1, "a"));
    }
}
