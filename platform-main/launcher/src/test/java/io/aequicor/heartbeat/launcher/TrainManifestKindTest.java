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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrainManifestKindTest {
    /** 64 байта подписи Ed25519 в Base64: 86 символов и «==». */
    static final String SIGNATURE = "A".repeat(43) + "+" + "B".repeat(41) + "/" + "==";

    @TempDir
    Path tmp;

    static String sha(byte[] bytes) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    static String entry(String id, String extra) {
        return "{\"id\":\"" + id + "\",\"version\":\"1\",\"jar\":\"" + id + ".jar\",\"sha256\":\"" + "a".repeat(64)
            + "\"" + extra + "}";
    }

    static String manifest(String... entries) {
        return "{\"train\":\"t1\",\"platformApi\":2,\"bundles\":[" + String.join(",", entries) + "]}";
    }

    @Test
    void entryWithoutKindIsAClasspathBundle() {
        TrainManifest.Bundle bundle = TrainManifest.parse(manifest(entry("web", ""))).bundles().get(0);
        assertEquals(TrainManifest.KIND_BUNDLE, bundle.kind());
        assertEquals(0, bundle.launcherApi());
        assertNull(bundle.signature());
        assertTrue(bundle.onClasspath());
    }

    @Test
    void baseArtifactStaysOnTheClasspath() {
        TrainManifest.Bundle bundle = TrainManifest.parse(manifest(entry("foundation", ",\"kind\":\"base\"")))
            .bundles().get(0);
        assertEquals(TrainManifest.KIND_BASE, bundle.kind());
        assertTrue(bundle.onClasspath());
    }

    @Test
    void launcherEntryCarriesApiAndSignatureAndIsOffTheClasspath() {
        TrainManifest.Bundle bundle = TrainManifest.parse(manifest(entry("launcher",
            ",\"kind\":\"launcher\",\"launcherApi\":3,\"signature\":\"" + SIGNATURE + "\""))).bundles().get(0);
        assertEquals(TrainManifest.KIND_LAUNCHER, bundle.kind());
        assertEquals(3, bundle.launcherApi());
        assertEquals(SIGNATURE, bundle.signature());
        assertFalse(bundle.onClasspath());
    }

    @Test
    void rejectsUnknownKind() {
        assertThrows(IllegalArgumentException.class,
            () -> TrainManifest.parse(manifest(entry("x", ",\"kind\":\"plugin\""))));
    }

    @Test
    void launcherEntryNeedsLauncherApi() {
        assertThrows(IllegalArgumentException.class,
            () -> TrainManifest.parse(manifest(entry("launcher", ",\"kind\":\"launcher\""))));
    }

    @Test
    void rejectsMoreThanOneLauncherEntry() {
        String extra = ",\"kind\":\"launcher\",\"launcherApi\":1";
        assertThrows(IllegalArgumentException.class,
            () -> TrainManifest.parse(manifest(entry("l1", extra), entry("l2", extra))));
    }

    @Test
    void rejectsMalformedSignature() {
        assertThrows(IllegalArgumentException.class,
            () -> TrainManifest.parse(manifest(entry("web", ",\"signature\":\"bad sig!\""))));
        assertThrows(IllegalArgumentException.class,
            () -> TrainManifest.parse(manifest(entry("web", ",\"signature\":\"\""))));
    }

    @Test
    void launcherJarIsStoredAndVerifiedButNotOnTheClasspath() throws Exception {
        Map<String, byte[]> remote = new HashMap<>();
        StringBuilder bundles = new StringBuilder();
        for (String id : List.of("foundation", "launcher", "web")) {
            byte[] content = (id + "@t1").getBytes(StandardCharsets.UTF_8);
            remote.put(id, content);
            if (bundles.length() > 0) {
                bundles.append(',');
            }
            String kind = id.equals("launcher") ? ",\"kind\":\"launcher\",\"launcherApi\":1" : "";
            bundles.append("{\"id\":\"").append(id).append("\",\"version\":\"1\",\"jar\":\"").append(id)
                .append(".jar\",\"sha256\":\"").append(sha(content)).append("\"").append(kind).append("}");
        }
        Path root = tmp.resolve("launcher");
        Path data = tmp.resolve("data");
        Files.createDirectories(data);
        Launcher launcher = new Launcher(root, data);
        launcher.applyUpdate("{\"train\":\"t1\",\"platformApi\":1,\"bundles\":[" + bundles + "]}",
            bundle -> new ByteArrayInputStream(remote.get(bundle.id())));
        Launcher.Prepared prepared = launcher.prepare();
        assertEquals("t1", prepared.train());
        assertEquals(2, prepared.classpath().size());
        assertEquals("foundation@t1", Files.readString(prepared.classpath().get(0)));
        assertEquals("web@t1", Files.readString(prepared.classpath().get(1)));
        // the launcher jar is in the store and verified with the train
        launcher.store().verify("t1");
        Path launcherJar = launcher.store().jarPath(sha(remote.get("launcher")));
        assertTrue(Files.isRegularFile(launcherJar));
        Files.writeString(launcherJar, "tampered", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> launcher.store().verify("t1"));
    }
}
