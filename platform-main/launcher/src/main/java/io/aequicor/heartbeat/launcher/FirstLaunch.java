package io.aequicor.heartbeat.launcher;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Первый запуск из seed. Установщик кладёт рядом с приложением каталог seed: {@code manifest.json} поезда,
 * jar бандлов, которые вошли в установщик (минимум {@code foundation}), и необязательный {@code seed.properties} с
 * {@code baseUrl} — адресом, где лежат остальные jar поезда. Если активного поезда ещё нет, seed-jar копируются в
 * хранилище (с проверкой SHA-256), недостающие бандлы докачиваются, поезд активируется. Повторный вызов после сбоя
 * продолжает с того, что уже лежит в хранилище. Без seed (монолитная установка) {@link #isNeeded()} ложно, и лаунчер
 * работает как раньше.
 */
public final class FirstLaunch {
    public static final String MANIFEST = "manifest.json";
    public static final String PROPERTIES = "seed.properties";
    public static final String BASE_URL_KEY = "baseUrl";
    /**
     * Суффикс seed-файла: {@code <jar>.seed}. jpackage добавляет в classpath установщика каждый {@code .jar} внутри
     * приложения, а jar поезда там быть не должны (родительский загрузчик перекрыл бы поезд), поэтому установщик
     * кладёт seed под другим расширением. Файл без суффикса тоже принимается.
     */
    public static final String SEED_SUFFIX = ".seed";

    /** Наблюдатель за ходом первого запуска; вызывается из потока {@link #run}. */
    public interface Listener {
        void onLog(String line);

        /**
         * @param done номер скачиваемого бандла (с нуля) — столько бандлов уже скачано
         * @param total сколько бандлов предстоит скачать
         * @param bundleId текущий бандл
         * @param bytes сколько байт текущего бандла получено
         */
        void onProgress(int done, int total, String bundleId, long bytes);
    }

    private final Launcher launcher;
    private final Path seedDir;

    public FirstLaunch(Launcher launcher, Path seedDir) {
        this.launcher = launcher;
        this.seedDir = seedDir.toAbsolutePath();
    }

    /** Нужен ли первый запуск: есть seed-манифест, активного поезда нет, и поезд seed не помечен плохим. */
    public boolean isNeeded() throws IOException {
        Path manifestFile = seedDir.resolve(MANIFEST);
        if (!Files.isRegularFile(manifestFile)) {
            return false;
        }
        LauncherState state = launcher.store().state();
        if (!state.current.isEmpty()) {
            return false;
        }
        return !state.bad.contains(TrainManifest.parse(Files.readString(manifestFile, StandardCharsets.UTF_8)).train());
    }

    /**
     * Classpath поезда из seed без активации: для процесса, который не владеет состоянием лаунчера (проба упаковки,
     * воркер свежей установки), пока владелец не активировал поезд. Пусто, если seed-манифеста нет. Каждый jar
     * проверяется по SHA-256.
     *
     * @throws IOException если seed неполон или jar повреждён
     */
    public static List<Path> seedClasspath(Path seedDir) throws IOException {
        return seedClasspath(seedDir, null);
    }

    /**
     * То же, но уже проверенные jar не хэшируются повторно: после успешной проверки в {@code stamp} пишутся размер,
     * время изменения и SHA-256 каждого jar, и следующий вызов пропускает хэш для jar, которые не изменились.
     * Печать не доверенная граница, а ускорение: устаревшая или нечитаемая печать означает полную проверку, а сбой
     * её записи не мешает запуску.
     */
    public static List<Path> seedClasspath(Path seedDir, Path stamp) throws IOException {
        Path manifestFile = seedDir.resolve(MANIFEST);
        if (!Files.isRegularFile(manifestFile)) {
            return List.of();
        }
        java.util.Map<String, String> known = readStamp(stamp);
        List<Path> result = new ArrayList<>();
        StringBuilder fresh = new StringBuilder();
        for (TrainManifest.Bundle bundle : TrainManifest.parse(Files.readString(manifestFile, StandardCharsets.UTF_8)).bundles()) {
            Path jar = seedDir.resolve(bundle.jar() + SEED_SUFFIX);
            if (!Files.isRegularFile(jar)) {
                jar = seedDir.resolve(bundle.jar());
            }
            if (!Files.isRegularFile(jar)) {
                throw new IOException("Seed jar " + bundle.jar() + " is missing or corrupted");
            }
            String sha = TrainManifest.normalizedSha(bundle);
            String identity = Files.size(jar) + ":" + Files.getLastModifiedTime(jar).toMillis() + ":" + sha;
            if (!identity.equals(known.get(bundle.jar())) && !TrainStore.sha256(jar).equals(sha)) {
                throw new IOException("Seed jar " + bundle.jar() + " is missing or corrupted");
            }
            fresh.append(bundle.jar()).append('\t').append(identity).append('\n');
            result.add(jar);
        }
        writeStamp(stamp, fresh.toString());
        return result;
    }

    private static java.util.Map<String, String> readStamp(Path stamp) {
        java.util.Map<String, String> known = new java.util.HashMap<>();
        if (stamp == null) {
            return known;
        }
        try {
            for (String line : Files.readAllLines(stamp, StandardCharsets.UTF_8)) {
                int tab = line.indexOf('\t');
                if (tab > 0) {
                    known.put(line.substring(0, tab), line.substring(tab + 1));
                }
            }
        } catch (IOException | RuntimeException e) {
            // no usable stamp: every jar is hashed
            known.clear();
        }
        return known;
    }

    private static void writeStamp(Path stamp, String content) {
        if (stamp == null) {
            return;
        }
        try {
            Files.createDirectories(stamp.getParent());
            Path tmp = Files.createTempFile(stamp.getParent(), "seed-verified", ".tmp");
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            Files.move(tmp, stamp, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            // only a speed-up: the next process verifies the jars again
        }
    }

    /** Адрес скачивания из {@code seed.properties}, либо {@code null}. */
    public String baseUrl() throws IOException {
        Path file = seedDir.resolve(PROPERTIES);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        String value = properties.getProperty(BASE_URL_KEY, "").trim();
        return value.isEmpty() ? null : value;
    }

    /**
     * Импортирует seed-jar, докачивает недостающие через {@code remote} и активирует поезд.
     *
     * @return активированный поезд
     * @throws IOException если докачать нечем/не удалось, хэш не сошёлся или платформа устарела
     */
    public String run(TrainStore.Fetcher remote, Listener listener) throws IOException {
        String manifestJson = Files.readString(seedDir.resolve(MANIFEST), StandardCharsets.UTF_8);
        TrainManifest manifest = TrainManifest.parse(manifestJson);
        listener.onLog("Train " + manifest.train() + ", bundles: " + manifest.bundles().size());
        TrainStore store = launcher.store();
        Files.createDirectories(store.jarPath("0").getParent());
        List<TrainManifest.Bundle> missing = new ArrayList<>();
        for (TrainManifest.Bundle bundle : manifest.bundles()) {
            if (importSeedJar(store, bundle, listener)) {
                continue;
            }
            missing.add(bundle);
        }
        listener.onLog("Seed provides " + (manifest.bundles().size() - missing.size()) + ", to download: " + missing.size());
        int[] done = {0};
        int total = missing.size();
        TrainStore.Fetcher counting = bundle -> {
            listener.onLog("Downloading " + bundle.jar());
            listener.onProgress(done[0], total, bundle.id(), 0);
            return new FilterInputStream(remote.open(bundle)) {
                private long bytes;

                @Override
                public int read() throws IOException {
                    int b = super.read();
                    if (b >= 0) {
                        report(1);
                    }
                    return b;
                }

                @Override
                public int read(byte[] buffer, int off, int len) throws IOException {
                    int n = super.read(buffer, off, len);
                    if (n > 0) {
                        report(n);
                    } else if (n < 0) {
                        done[0]++;
                        listener.onProgress(done[0], total, bundle.id(), bytes);
                    }
                    return n;
                }

                private void report(int n) {
                    bytes += n;
                    listener.onProgress(done[0], total, bundle.id(), bytes);
                }
            };
        };
        launcher.applyUpdate(manifestJson, counting);
        listener.onLog("Train " + manifest.train() + " activated");
        return manifest.train();
    }

    /** Копирует jar из seed в хранилище, если он есть и его SHA-256 верен. */
    private boolean importSeedJar(TrainStore store, TrainManifest.Bundle bundle, Listener listener) throws IOException {
        String sha = TrainManifest.normalizedSha(bundle);
        Path target = store.jarPath(sha);
        if (Files.isRegularFile(target) && TrainStore.sha256(target).equals(sha)) {
            return true;
        }
        Path source = seedDir.resolve(bundle.jar() + SEED_SUFFIX);
        if (!Files.isRegularFile(source)) {
            source = seedDir.resolve(bundle.jar());
        }
        if (!Files.isRegularFile(source)) {
            return false;
        }
        if (!TrainStore.sha256(source).equals(sha)) {
            listener.onLog("Seed jar " + bundle.jar() + " is corrupted, will be downloaded");
            return false;
        }
        Path part = target.resolveSibling(sha + ".seed");
        Files.copy(source, part, StandardCopyOption.REPLACE_EXISTING);
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        listener.onLog("Imported " + bundle.jar() + " from seed");
        return true;
    }
}
