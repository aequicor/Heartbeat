package io.aequicor.heartbeat.launcher;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarFile;

/**
 * Java-агент установленного приложения ({@code -javaagent:launcher.jar}). Он даёт лаунчеру
 * {@link Instrumentation}, чтобы дописать jar поезда в поиск системного загрузчика классов: приложение работает в одном
 * загрузчике, как в разработке. Отдельный {@link java.net.URLClassLoader} поезда ломает всё, что берёт ресурсы и
 * сервисы через контекстный загрузчик потока: потоки AWT, Skiko и пулов корутин создаются раньше или вне главного
 * потока, и контекстный загрузчик у них системный (например, ресурсы Compose не находятся).
 *
 * <p>Jar дописываются после classpath установщика, поэтому класс платформы с тем же именем перекрыл бы класс поезда:
 * сборка установщика проверяет, что на его classpath нет проектных классов, кроме лаунчера.
 */
public final class LauncherAgent {
    private static volatile Instrumentation instrumentation;

    private LauncherAgent() {
    }

    /** Точка входа агента: запоминает {@link Instrumentation}. */
    public static void premain(String args, Instrumentation inst) {
        instrumentation = inst;
    }

    /** Агент подключён, и jar поезда можно дописать в системный загрузчик. */
    static boolean isAvailable() {
        return instrumentation != null;
    }

    /**
     * Дописывает {@code classpath} в поиск системного загрузчика.
     *
     * @throws IOException если jar не открывается
     * @throws IllegalStateException если агент не подключён
     */
    static void appendToSystemClassLoader(List<Path> classpath) throws IOException {
        Instrumentation inst = instrumentation;
        if (inst == null) {
            throw new IllegalStateException("The launcher agent is not attached");
        }
        for (Path jar : classpath) {
            inst.appendToSystemClassLoaderSearch(new JarFile(jar.toFile()));
        }
    }
}
