package io.aequicor.heartbeat.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FirstLaunchTest {
    @TempDir
    Path tmp;

    Path seed;
    Launcher launcher;
    FirstLaunch firstLaunch;
    final Map<String, byte[]> remote = new HashMap<>();
    final List<String> fetched = new ArrayList<>();
    final List<String> logs = new ArrayList<>();
    final List<String> progress = new ArrayList<>();

    final FirstLaunch.Listener listener = new FirstLaunch.Listener() {
        @Override
        public void onLog(String line) {
            logs.add(line);
        }

        @Override
        public void onProgress(int done, int total, String bundleId, long bytes) {
            progress.add(done + "/" + total + ":" + bundleId);
        }
    };

    final TrainStore.Fetcher fetcher = bundle -> {
        fetched.add(bundle.id());
        byte[] data = remote.get(bundle.id());
        if (data == null) {
            throw new IOException("offline");
        }
        return new ByteArrayInputStream(data);
    };

    @BeforeEach
    void setUp() throws IOException {
        seed = Files.createDirectories(tmp.resolve("seed"));
        launcher = new Launcher(tmp.resolve("launcher"), Files.createDirectories(tmp.resolve("data")));
        firstLaunch = new FirstLaunch(launcher, seed);
    }

    static String sha(byte[] bytes) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    /** Seed-манифест из бандлов; в seed-каталог кладутся jar перечисленных в {@code inSeed}, остальные — в remote. */
    void writeSeed(String train, List<String> inSeed, String... ids) throws Exception {
        StringBuilder bundles = new StringBuilder();
        for (String id : ids) {
            byte[] content = (id + "@" + train).getBytes(StandardCharsets.UTF_8);
            if (inSeed.contains(id)) {
                Files.write(seed.resolve(id + ".jar"), content);
            } else {
                remote.put(id, content);
            }
            if (bundles.length() > 0) {
                bundles.append(',');
            }
            bundles.append("{\"id\":\"").append(id).append("\",\"version\":\"1\",\"jar\":\"").append(id)
                .append(".jar\",\"sha256\":\"").append(sha(content)).append("\"}");
        }
        Files.writeString(seed.resolve(FirstLaunch.MANIFEST),
            "{\"train\":\"" + train + "\",\"platformApi\":1,\"bundles\":[" + bundles + "]}");
    }

    @Test
    void notNeededWithoutSeed() throws Exception {
        assertFalse(firstLaunch.isNeeded());
        assertNullTrain();
    }

    void assertNullTrain() throws Exception {
        assertEquals(null, launcher.prepare().train());
    }

    @Test
    void seedOnlyTrainActivatesWithoutDownloads() throws Exception {
        writeSeed("t1", List.of("foundation"), "foundation");
        assertTrue(firstLaunch.isNeeded());
        assertEquals("t1", firstLaunch.run(fetcher, listener));
        assertTrue(fetched.isEmpty());
        assertFalse(firstLaunch.isNeeded());
        assertEquals("t1", launcher.prepare().train());
    }

    @Test
    void seedJarsMayCarryTheSeedSuffix() throws Exception {
        writeSeed("t1", List.of("foundation"), "foundation");
        Path plain = seed.resolve("foundation.jar");
        Files.move(plain, seed.resolve("foundation.jar" + FirstLaunch.SEED_SUFFIX));
        assertEquals("t1", firstLaunch.run(fetcher, listener));
        assertTrue(fetched.isEmpty());
        assertEquals("t1", launcher.prepare().train());
    }

    @Test
    void seedClasspathServesAnAuxiliaryProcessBeforeActivation() throws Exception {
        assertEquals(List.of(), FirstLaunch.seedClasspath(seed));
        writeSeed("t1", List.of("foundation", "web"), "foundation", "web");
        assertEquals(2, FirstLaunch.seedClasspath(seed).size());
        Files.writeString(seed.resolve("web.jar"), "broken");
        assertThrows(java.io.IOException.class, () -> FirstLaunch.seedClasspath(seed));
    }

    @Test
    void downloadsMissingBundlesAndReportsProgress() throws Exception {
        writeSeed("t1", List.of("foundation"), "foundation", "web", "studio");
        firstLaunch.run(fetcher, listener);
        assertEquals(List.of("web", "studio"), fetched);
        assertTrue(progress.contains("0/2:web"));
        assertTrue(progress.contains("2/2:studio"));
        Launcher.Prepared prepared = launcher.prepare();
        assertEquals(3, prepared.classpath().size());
    }

    @Test
    void failureKeepsSeedAndRetryResumes() throws Exception {
        writeSeed("t1", List.of("foundation"), "foundation", "web");
        byte[] web = remote.remove("web");
        assertThrows(IOException.class, () -> firstLaunch.run(fetcher, listener));
        assertTrue(firstLaunch.isNeeded());
        remote.put("web", web);
        assertEquals("t1", firstLaunch.run(fetcher, listener));
        assertEquals(List.of("web", "web"), fetched);
    }

    @Test
    void corruptedDownloadIsRejected() throws Exception {
        writeSeed("t1", List.of("foundation"), "foundation", "web");
        remote.put("web", "tampered".getBytes(StandardCharsets.UTF_8));
        assertThrows(TrainStore.HashMismatchException.class, () -> firstLaunch.run(fetcher, listener));
        assertTrue(firstLaunch.isNeeded());
    }

    @Test
    void corruptedSeedJarIsDownloadedInstead() throws Exception {
        writeSeed("t1", List.of("foundation"), "foundation");
        byte[] good = "foundation@t1".getBytes(StandardCharsets.UTF_8);
        Files.writeString(seed.resolve("foundation.jar"), "broken");
        remote.put("foundation", good);
        firstLaunch.run(fetcher, listener);
        assertEquals(List.of("foundation"), fetched);
    }

    @Test
    void failedSeedTrainStaysWithoutPreviousTrain() throws Exception {
        writeSeed("t1", List.of("foundation"), "foundation");
        firstLaunch.run(fetcher, listener);
        launcher.prepare();
        launcher.reportBundleFailure();
        assertFalse(firstLaunch.isNeeded());
        assertEquals("t1", launcher.prepare().train());
    }

    @Test
    void offlineSeedWithAllJarsNeedsNoBaseUrl() throws Exception {
        writeSeed("t1", List.of("foundation", "web"), "foundation", "web");
        assertEquals(null, firstLaunch.baseUrl());
        assertEquals("t1", firstLaunch.run(new HttpFetcher(null), listener));
    }

    @Test
    void missingJarWithoutBaseUrlFailsClearly() throws Exception {
        writeSeed("t1", List.of("foundation"), "foundation", "web");
        IOException error = assertThrows(IOException.class, () -> firstLaunch.run(new HttpFetcher(null), listener));
        assertTrue(error.getMessage().contains("baseUrl"));
        assertTrue(firstLaunch.isNeeded());
    }

    @Test
    void httpFetcherRequiresHttpsOrLoopback() {
        assertThrows(IllegalArgumentException.class, () -> new HttpFetcher("http://example.com/x/"));
        new HttpFetcher("https://example.com/x");
    }

    @Test
    void httpFetcherDownloadsAndFailsOnError() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/ok.jar", exchange -> {
            byte[] body = "data".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            HttpFetcher http = new HttpFetcher("http://127.0.0.1:" + server.getAddress().getPort() + "/");
            String hash = "a".repeat(64);
            byte[] bytes = http.open(new TrainManifest.Bundle("ok", "1", "ok.jar", hash)).readAllBytes();
            assertEquals("data", new String(bytes, StandardCharsets.UTF_8));
            assertThrows(IOException.class, () -> http.open(new TrainManifest.Bundle("no", "1", "no.jar", hash)));
            // Бандл из более старого релиза: собственный адрес важнее baseUrl.
            HttpFetcher elsewhere = new HttpFetcher("https://example.invalid/train/");
            String own = "http://127.0.0.1:" + server.getAddress().getPort() + "/ok.jar";
            byte[] viaUrl = elsewhere.open(new TrainManifest.Bundle("old", "1", "old.jar", hash, own)).readAllBytes();
            assertEquals("data", new String(viaUrl, StandardCharsets.UTF_8));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void readsBaseUrlFromSeedProperties() throws Exception {
        assertEquals(null, firstLaunch.baseUrl());
        Files.writeString(seed.resolve(FirstLaunch.PROPERTIES), "baseUrl=https://example.com/train/\n");
        assertEquals("https://example.com/train/", firstLaunch.baseUrl());
    }

    @Test
    void logKeepsLastLinesAndExports() throws Exception {
        FirstLaunchLog log = new FirstLaunchLog();
        for (int i = 0; i < FirstLaunchLog.MAX_LINES + 5; i++) {
            log.add("line " + i);
        }
        assertEquals(FirstLaunchLog.MAX_LINES, log.snapshot().size());
        assertEquals("line 5", log.snapshot().get(0));
        Path out = tmp.resolve("out.log");
        log.export(out);
        assertTrue(Files.readString(out).startsWith("line 5"));
    }
}
