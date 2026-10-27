package io.aequicor.heartbeat.launcher;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Манифест поезда бандлов (JSON): {@code {"train": "...", "platformApi": 1, "dbSchema": 3?,
 * "bundles": [{"id","version","jar","sha256","url"?}]}}. Поле {@code dbSchema} необязательно — версия схем БД набора
 * (для бэкапа перед миграцией). Порядок бандлов задаёт порядок classpath. Необязательное поле {@code channel}
 * ({@code "beta"}) ставит app-update в очередной копии поезда, взятого из pre-release; другие значения игнорируются.
 */
public final class TrainManifest {
    /**
     * Бандл поезда: jar лежит рядом с манифестом под именем {@code jar}, либо по абсолютному адресу {@code url}
     * (необязательно; бандл, не менявшийся с более старого поезда, лежит в релизе того поезда). Адрес — HTTPS
     * или HTTP на loopback, как у {@link HttpFetcher}.
     */
    public record Bundle(String id, String version, String jar, String sha256, String url) {
        public Bundle(String id, String version, String jar, String sha256) {
            this(id, version, jar, sha256, null);
        }
    }

    private final String train;
    private final int platformApi;
    private final int dbSchema;
    private final List<Bundle> bundles;
    private final boolean beta;

    public TrainManifest(String train, int platformApi, int dbSchema, List<Bundle> bundles) {
        this(train, platformApi, dbSchema, bundles, false);
    }

    public TrainManifest(String train, int platformApi, int dbSchema, List<Bundle> bundles, boolean beta) {
        if (train == null || train.isEmpty() || !train.matches("[A-Za-z0-9._+-]+") || train.startsWith(".")) {
            throw new IllegalArgumentException("Invalid train id: " + train);
        }
        Set<String> ids = new HashSet<>();
        Set<String> jars = new HashSet<>();
        for (Bundle bundle : bundles) {
            validate(bundle);
            if (!ids.add(bundle.id())) {
                throw new IllegalArgumentException("Duplicate bundle id: " + bundle.id());
            }
            if (!jars.add(bundle.jar())) {
                throw new IllegalArgumentException("Duplicate bundle jar: " + bundle.jar());
            }
        }
        this.train = train;
        this.platformApi = platformApi;
        this.dbSchema = dbSchema;
        this.bundles = List.copyOf(bundles);
        this.beta = beta;
    }

    /** Поезд взят из beta-канала (pre-release). */
    public boolean beta() {
        return beta;
    }

    public String train() {
        return train;
    }

    public int platformApi() {
        return platformApi;
    }

    /** Версия схем БД набора; {@code 0}, если манифест её не указывает. */
    public int dbSchema() {
        return dbSchema;
    }

    public List<Bundle> bundles() {
        return bundles;
    }

    public static TrainManifest parse(String json) {
        Object root = MiniJson.parse(json);
        if (!(root instanceof Map)) {
            throw new IllegalArgumentException("Manifest must be a JSON object");
        }
        Map<?, ?> obj = (Map<?, ?>) root;
        String train = string(obj, "train");
        long api = number(obj, "platformApi", true);
        long schema = number(obj, "dbSchema", false);
        Object rawBundles = obj.get("bundles");
        if (!(rawBundles instanceof List)) {
            throw new IllegalArgumentException("Manifest field 'bundles' must be an array");
        }
        List<Bundle> bundles = new ArrayList<>();
        for (Object item : (List<?>) rawBundles) {
            if (!(item instanceof Map)) {
                throw new IllegalArgumentException("Bundle entry must be an object");
            }
            Map<?, ?> b = (Map<?, ?>) item;
            bundles.add(new Bundle(string(b, "id"), string(b, "version"), string(b, "jar"), string(b, "sha256"),
                optionalString(b, "url")));
        }
        boolean beta = "beta".equals(obj.get("channel"));
        return new TrainManifest(train, toInt("platformApi", api), toInt("dbSchema", schema), bundles, beta);
    }

    private static void validate(Bundle bundle) {
        String jar = bundle.jar();
        if (jar.isEmpty() || jar.contains("/") || jar.contains("\\") || jar.startsWith(".") || !jar.endsWith(".jar")) {
            throw new IllegalArgumentException("Invalid jar name: " + jar);
        }
        if (bundle.id().isEmpty()) {
            throw new IllegalArgumentException("Empty bundle id");
        }
        if (!bundle.sha256().matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("Invalid sha256 for " + jar);
        }
        if (bundle.url() != null && !isAllowedUrl(bundle.url())) {
            throw new IllegalArgumentException("Bundle " + bundle.id() + " url needs https: " + bundle.url());
        }
    }

    /** Адрес скачивания допустим: HTTPS или HTTP на loopback. */
    static boolean isAllowedUrl(String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (uri.getHost() == null) {
            return false;
        }
        boolean loopback = "http".equals(uri.getScheme())
            && ("localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost()));
        return "https".equals(uri.getScheme()) || loopback;
    }

    private static int toInt(String key, long value) {
        if (value < 0 || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Manifest field '" + key + "' is out of range: " + value);
        }
        return (int) value;
    }

    private static String optionalString(Map<?, ?> obj, String key) {
        Object value = obj.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String)) {
            throw new IllegalArgumentException("Manifest field '" + key + "' must be a string");
        }
        return (String) value;
    }

    private static String string(Map<?, ?> obj, String key) {
        Object value = obj.get(key);
        if (!(value instanceof String)) {
            throw new IllegalArgumentException("Manifest field '" + key + "' must be a string");
        }
        return (String) value;
    }

    private static long number(Map<?, ?> obj, String key, boolean required) {
        Object value = obj.get(key);
        if (value == null && !required) {
            return 0;
        }
        if (!(value instanceof Long)) {
            throw new IllegalArgumentException("Manifest field '" + key + "' must be an integer");
        }
        return (Long) value;
    }

    /** Нормализованный sha256 (нижний регистр) бандла. */
    static String normalizedSha(Bundle bundle) {
        return bundle.sha256().toLowerCase(Locale.ROOT);
    }
}
