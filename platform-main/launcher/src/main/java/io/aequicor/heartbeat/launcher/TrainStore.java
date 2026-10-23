package io.aequicor.heartbeat.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Хранилище поездов в корне лаунчера. Раскладка: {@code store/<sha256>.jar} (jar общие для поездов),
 * {@code trains/<train>/manifest.json}, {@code staging/} (недокачанное), {@code state.properties}.
 * Хранит не более {@link #KEEP_TRAINS} поездов: текущий и предыдущий.
 */
public final class TrainStore {
    public static final int KEEP_TRAINS = 2;

    /** Источник байт jar для бандла (сеть, кэш релиза или тестовая подмена). */
    public interface Fetcher {
        InputStream open(TrainManifest.Bundle bundle) throws IOException;
    }

    /** Скачанные данные не совпали с SHA-256 манифеста. */
    public static final class HashMismatchException extends IOException {
        public HashMismatchException(String jar, String expected, String actual) {
            super("SHA-256 mismatch for " + jar + ": expected " + expected + ", actual " + actual);
        }
    }

    private final Path root;

    public TrainStore(Path root) {
        this.root = root.toAbsolutePath();
    }

    Path stateFile() {
        return root.resolve("state.properties");
    }

    Path jarPath(String sha256) {
        return root.resolve("store").resolve(sha256.toLowerCase(Locale.ROOT) + ".jar");
    }

    Path manifestFile(String train) {
        return root.resolve("trains").resolve(train).resolve("manifest.json");
    }

    LauncherState state() throws IOException {
        return LauncherState.load(stateFile());
    }

    /**
     * Докачивает недостающие jar поезда в {@code staging}, проверяет SHA-256 каждого и переносит в {@code store},
     * затем записывает манифест. Уже лежащие в хранилище jar с верным хэшем не качаются повторно.
     * Активным поезд не становится — для этого {@link #activate}.
     */
    public void stage(String manifestJson, Fetcher fetcher) throws IOException {
        TrainManifest manifest = TrainManifest.parse(manifestJson);
        Path staging = Files.createDirectories(root.resolve("staging").resolve(manifest.train()));
        Files.createDirectories(root.resolve("store"));
        try {
            for (TrainManifest.Bundle bundle : manifest.bundles()) {
                String sha = TrainManifest.normalizedSha(bundle);
                Path target = jarPath(sha);
                if (Files.isRegularFile(target) && sha256(target).equals(sha)) {
                    continue;
                }
                Path part = staging.resolve(sha + ".part");
                try (InputStream in = fetcher.open(bundle); OutputStream out = Files.newOutputStream(part)) {
                    in.transferTo(out);
                }
                String actual = sha256(part);
                if (!actual.equals(sha)) {
                    Files.deleteIfExists(part);
                    throw new HashMismatchException(bundle.jar(), sha, actual);
                }
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            Path manifestFile = manifestFile(manifest.train());
            Files.createDirectories(manifestFile.getParent());
            Path tmp = manifestFile.resolveSibling("manifest.json.tmp");
            Files.write(tmp, manifestJson.getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, manifestFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            deleteRecursively(staging);
        }
    }

    /** Health-check поезда: манифест читается, все jar на месте и их SHA-256 совпадают. */
    public void verify(String train) throws IOException {
        TrainManifest manifest = readManifest(train);
        for (TrainManifest.Bundle bundle : manifest.bundles()) {
            String sha = TrainManifest.normalizedSha(bundle);
            Path jar = jarPath(sha);
            if (!Files.isRegularFile(jar)) {
                throw new IOException("Missing jar " + bundle.jar() + " of train " + train);
            }
            String actual = sha256(jar);
            if (!actual.equals(sha)) {
                throw new HashMismatchException(bundle.jar(), sha, actual);
            }
        }
    }

    public TrainManifest readManifest(String train) throws IOException {
        return TrainManifest.parse(Files.readString(manifestFile(train), StandardCharsets.UTF_8));
    }

    /** Классpath поезда: jar бандлов в порядке манифеста. */
    public List<Path> classpath(String train) throws IOException {
        List<Path> result = new ArrayList<>();
        for (TrainManifest.Bundle bundle : readManifest(train).bundles()) {
            result.add(jarPath(TrainManifest.normalizedSha(bundle)));
        }
        return result;
    }

    /**
     * Делает уже подготовленный поезд текущим (прежний текущий становится предыдущим), сбрасывает счётчик крахов,
     * чистит всё, кроме двух последних поездов. Плохой поезд не активируется.
     */
    public void activate(String train) throws IOException {
        LauncherState state = state();
        if (state.bad.contains(train)) {
            throw new IOException("Train " + train + " is marked bad");
        }
        verify(train);
        if (train.equals(state.current)) {
            return;
        }
        state.previous = state.current;
        state.current = train;
        state.crashes = 0;
        state.running = false;
        state.stable = false;
        state.save(stateFile());
        prune();
    }

    /**
     * Откатывает весь поезд: текущий помечается плохим, предыдущий становится текущим.
     *
     * @return имя поезда, к которому откатились, либо {@code null}, если откатываться некуда
     *         (плохая метка при этом всё равно ставится)
     */
    public String rollback() throws IOException {
        LauncherState state = state();
        if (!state.current.isEmpty()) {
            state.bad.add(state.current);
        }
        String target = state.previous.isEmpty() ? null : state.previous;
        state.current = target == null ? "" : target;
        state.previous = "";
        state.crashes = 0;
        state.running = false;
        state.stable = true;
        state.save(stateFile());
        prune();
        return target;
    }

    /** Удаляет поезда и jar, не входящие в текущий и предыдущий поезд (в том числе подготовленные, но не активированные). */
    void prune() throws IOException {
        LauncherState state = state();
        Set<String> keepTrains = new HashSet<>();
        if (!state.current.isEmpty()) {
            keepTrains.add(state.current);
        }
        if (!state.previous.isEmpty()) {
            keepTrains.add(state.previous);
        }
        Set<String> keepJars = new HashSet<>();
        Path trains = root.resolve("trains");
        if (Files.isDirectory(trains)) {
            try (DirectoryStream<Path> dirs = Files.newDirectoryStream(trains)) {
                for (Path dir : dirs) {
                    String name = dir.getFileName().toString();
                    if (!keepTrains.contains(name)) {
                        deleteRecursively(dir);
                    }
                }
            }
        }
        for (String train : keepTrains) {
            if (Files.exists(manifestFile(train))) {
                for (TrainManifest.Bundle bundle : readManifest(train).bundles()) {
                    keepJars.add(TrainManifest.normalizedSha(bundle) + ".jar");
                }
            }
        }
        Path store = root.resolve("store");
        if (Files.isDirectory(store)) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(store)) {
                for (Path file : files) {
                    if (!keepJars.contains(file.getFileName().toString())) {
                        Files.deleteIfExists(file);
                    }
                }
            }
        }
    }

    static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream din = new DigestInputStream(in, digest)) {
                din.transferTo(OutputStream.nullOutputStream());
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        if (Files.isDirectory(path)) {
            try (DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
                for (Path child : children) {
                    deleteRecursively(child);
                }
            }
        }
        Files.deleteIfExists(path);
    }
}
