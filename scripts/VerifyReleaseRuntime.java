import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;

/**
 * Checks enum lookup and the HTTP buffering path in the actual ProGuard output, without starting the app.
 * Run with a JDK: java scripts/VerifyReleaseRuntime.java <packaged-app-jar-directory>.
 */
public final class VerifyReleaseRuntime {
    private static void verifyHttpBuffering(ClassLoader loader) throws Exception {
        Class<?> bufferType = Class.forName("okio.Buffer", true, loader);
        Class<?> sinkType = Class.forName("okio.Sink", true, loader);
        Class<?> bufferedType = Class.forName("okio.BufferedSink", true, loader);
        Object target = bufferType.getConstructor().newInstance();
        Object buffered = Class.forName("okio.Okio", true, loader).getMethod("buffer", sinkType).invoke(null, target);
        bufferedType.getMethod("writeUtf8", String.class).invoke(buffered, "release-probe");
        bufferedType.getMethod("flush").invoke(buffered);
        Class<?> sourceType = Class.forName("okio.Source", true, loader);
        Object source = Class.forName("okio.Okio", true, loader).getMethod("buffer", sourceType).invoke(null, target);
        Object text = Class.forName("okio.BufferedSource", true, loader).getMethod("readUtf8").invoke(source);
        if (!"release-probe".equals(text)) {
            throw new AssertionError("HTTP buffer round trip failed");
        }
        System.out.println("Release HTTP buffering OK");
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected packaged app jar directory");
        try (var paths = Files.list(Path.of(args[0]))) {
            var jars = paths.filter(path -> path.toString().endsWith(".jar")).sorted().toList();
            if (jars.isEmpty()) throw new IllegalArgumentException("No packaged jars found");
            var urls = new java.net.URL[jars.size()];
            for (int i = 0; i < jars.size(); i++) urls[i] = jars.get(i).toUri().toURL();
            try (var loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
                verifyHttpBuffering(loader);
                int enums = 0;
                int constants = 0;
                for (var path : jars) {
                    try (var jar = new JarFile(path.toFile())) {
                        for (var entry : jar.stream().toList()) {
                            String name = entry.getName();
                            if (!name.startsWith("io/aequicor/heartbeat/") || !name.endsWith(".class")) continue;
                            Class<?> type = Class.forName(name.replace('/', '.').replaceAll("\\.class$", ""), false, loader);
                            if (!type.isEnum()) continue;
                            Object[] values = type.getEnumConstants();
                            if (values == null) throw new AssertionError("Enum values missing: " + type.getName());
                            for (Object value : values) {
                                String constant = ((Enum<?>) value).name();
                                var lookup = type.getMethod("valueOf", String.class);
                                lookup.setAccessible(true);
                                Object restored = lookup.invoke(null, constant);
                                if (restored != value) throw new AssertionError(type.getName() + ": " + constant);
                                constants++;
                            }
                            enums++;
                        }
                    }
                }
                if (enums == 0) throw new AssertionError("No application enums found");
                System.out.println("Release enums OK: " + enums + " enums, " + constants + " constants");
            }
        }
    }
}
