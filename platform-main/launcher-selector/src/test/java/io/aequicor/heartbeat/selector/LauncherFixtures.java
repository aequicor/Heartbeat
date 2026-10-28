package io.aequicor.heartbeat.selector;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/** Собирает jar с настоящим классом {@code LauncherMain} нужной формы: вместо подмены загрузчика запускаем реальный. */
final class LauncherFixtures {
    /** Системное свойство, куда фикстуры дописывают, кто запустился и что успел. */
    static final String LOG = "fixture.log";

    private LauncherFixtures() {
    }

    /** Лаунчер подтверждает слот и записывает {@code tag}. */
    static Path working(Path jar, String tag) throws Exception {
        return build(jar, body("applicationStarted.run(); log(\"" + tag + "\");"));
    }

    /**
     * Лаунчер с приёмом обновлений: записывает флаг пробного слота, подтверждает слот и предлагает селектору jar, который
     * тест описал свойствами {@code fixture.req.*}; без приёмника пишет {@code nostager}.
     */
    static Path staging(Path jar, String tag) throws Exception {
        return build(jar, "package io.aequicor.heartbeat.launcher;\n"
            + "public final class LauncherMain {\n"
            + "  static void log(String tag) {\n"
            + "    System.setProperty(\"" + LOG + "\", System.getProperty(\"" + LOG + "\", \"\") + tag + \";\");\n"
            + "  }\n"
            + "  public static void start(String[] args, Runnable applicationStarted,\n"
            + "      java.util.function.Predicate<java.util.Map<String, String>> stager) throws Exception {\n"
            + "    log(\"" + tag + ":trial=\" + System.getProperty(\"heartbeat.slot.trial\"));\n"
            + "    applicationStarted.run();\n"
            + "    if (stager == null) { log(\"nostager\"); return; }\n"
            + "    java.util.Map<String, String> request = new java.util.HashMap<>();\n"
            + "    for (String key : new String[] {\"jar\", \"id\", \"version\", \"sha256\", \"kind\", \"launcherApi\", \"signature\"}) {\n"
            + "      request.put(key, System.getProperty(\"fixture.req.\" + key));\n"
            + "    }\n"
            + "    log(\"staged=\" + stager.test(request));\n"
            + "  }\n"
            + "}\n");
    }

    /** Лаунчер откладывает операцию с данными: ставит признак и отказывается, как пробный лаунчер при повреждённом поезде. */
    static Path deferring(Path jar, String tag) throws Exception {
        return build(jar, body("log(\"" + tag + "\"); System.setProperty(\"heartbeat.slot.deferred\", \"true\"); "
            + "throw new java.io.IOException(\"deferred\");"));
    }

    /** Рабочий лаунчер с {@code LauncherAgent}: {@code premain} записывает, получил ли он {@code Instrumentation}. */
    static Path withAgent(Path jar, String tag) throws Exception {
        return build(jar, body("log(\"" + tag + "\"); applicationStarted.run();"), AGENT);
    }

    /** Лаунчер без метода {@code start}, но с агентом: до {@code premain} дело дойти не должно. */
    static Path withoutStartButWithAgent(Path jar) throws Exception {
        return build(jar, "package io.aequicor.heartbeat.launcher;\n"
            + "public final class LauncherMain {\n"
            + "  static void log(String tag) {\n"
            + "    System.setProperty(\"" + LOG + "\", System.getProperty(\"" + LOG + "\", \"\") + tag + \";\");\n"
            + "  }\n"
            + "  public static void main(String[] args) { }\n"
            + "}\n", AGENT);
    }

    private static final String AGENT = "package io.aequicor.heartbeat.launcher;\n"
        + "public final class LauncherAgent {\n"
        + "  public static void premain(String args, java.lang.instrument.Instrumentation inst) {\n"
        + "    LauncherMain.log(\"premain:\" + (inst != null));\n"
        + "  }\n"
        + "}\n";

    /** Лаунчер бросает исключение до подтверждения. */
    static Path failingBeforeConfirmation(Path jar, String tag) throws Exception {
        return build(jar, body("log(\"" + tag + "\"); throw new IllegalStateException(\"boom\");"));
    }

    /** В jar есть класс лаунчера, но нет метода {@code start}: селектор не может его запустить. */
    static Path withoutStart(Path jar) throws Exception {
        return build(jar, "package io.aequicor.heartbeat.launcher;\n"
            + "public final class LauncherMain { public static void main(String[] args) { } }\n");
    }

    /** Jar без класса лаунчера. */
    static Path empty(Path jar) throws IOException {
        Files.createDirectories(jar.getParent());
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream jos = new JarOutputStream(out)) {
            jos.putNextEntry(new JarEntry("README.txt"));
            jos.write("no launcher".getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }
        return jar;
    }

    static void log(String tag) {
        System.setProperty(LOG, System.getProperty(LOG, "") + tag + ";");
    }

    private static String body(String statements) {
        return "package io.aequicor.heartbeat.launcher;\n"
            + "public final class LauncherMain {\n"
            + "  static void log(String tag) {\n"
            + "    System.setProperty(\"" + LOG + "\", System.getProperty(\"" + LOG + "\", \"\") + tag + \";\");\n"
            + "  }\n"
            + "  public static void start(String[] args, Runnable applicationStarted) throws Exception {\n"
            + "    " + statements + "\n"
            + "  }\n"
            + "}\n";
    }

    /** Файл не jar: байты, которые не читаются как zip. */
    static Path garbage(Path jar) throws IOException {
        Files.createDirectories(jar.toAbsolutePath().getParent());
        Files.writeString(jar, "this is not a zip archive", StandardCharsets.UTF_8);
        return jar;
    }

    private static Path build(Path jar, String main, String... others) throws Exception {
        Files.createDirectories(jar.toAbsolutePath().getParent());
        Path work = Files.createTempDirectory(jar.toAbsolutePath().getParent(), "fixture");
        Path src = work.resolve("src/io/aequicor/heartbeat/launcher");
        Files.createDirectories(src);
        java.util.List<String> sources = new java.util.ArrayList<>();
        java.util.List<String> all = new java.util.ArrayList<>();
        all.add(main);
        all.addAll(java.util.List.of(others));
        for (String source : all) {
            java.util.regex.Matcher name = java.util.regex.Pattern.compile("final class (\\w+)").matcher(source);
            if (!name.find()) {
                throw new IllegalStateException("Fixture source has no class");
            }
            Path file = src.resolve(name.group(1) + ".java");
            Files.writeString(file, source, StandardCharsets.UTF_8);
            sources.add(file.toString());
        }
        Path classes = Files.createDirectories(work.resolve("classes"));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        java.util.List<String> command = new java.util.ArrayList<>(java.util.List.of("-d", classes.toString()));
        command.addAll(sources);
        if (compiler == null || compiler.run(null, null, null, command.toArray(new String[0])) != 0) {
            throw new IllegalStateException("Fixture launcher does not compile");
        }
        Files.createDirectories(jar.getParent());
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream jos = new JarOutputStream(out);
             Stream<Path> files = Files.walk(classes)) {
            for (Path path : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                jos.putNextEntry(new JarEntry(classes.relativize(path).toString().replace('\\', '/')));
                jos.write(Files.readAllBytes(path));
                jos.closeEntry();
            }
        }
        return jar;
    }
}
