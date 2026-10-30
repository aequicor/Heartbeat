package io.aequicor.heartbeat.selector;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Predicate;
import java.util.jar.JarFile;
import java.util.zip.ZipException;

/**
 * Запускает лаунчер из jar (слота или встроенного) в его собственном загрузчике классов поверх системного. Классы
 * лаунчера не видны приложению и не затеняют друг друга, поэтому слот можно отвергнуть и запустить другой в том же
 * процессе. Лаунчер получает {@link Instrumentation} явно: его {@code LauncherAgent} при запуске через слот не
 * подключён как {@code -javaagent}.
 */
final class SlotLauncher {
    static final String AGENT_CLASS = "io.aequicor.heartbeat.launcher.LauncherAgent";
    static final String MAIN_CLASS = "io.aequicor.heartbeat.launcher.LauncherMain";

    /**
     * Лаунчер не удалось подготовить; его логика не выполнялась (кроме {@code premain}, который идёт после того, как
     * класс и метод запуска найдены). Можно запускать другой.
     */
    static final class NotStartedException extends Exception {
        private final boolean permanent;

        NotStartedException(String message, Throwable cause, boolean permanent) {
            super(message, cause);
            this.permanent = permanent;
        }

        /**
         * Отказ окончательный (повреждённый jar, нет класса или метода): слот отвергается. Иначе (jar сейчас не
         * открывается: антивирус, занятый файл) слот лишь пропускается в этом процессе.
         */
        boolean permanent() {
            return permanent;
        }
    }

    private SlotLauncher() {
    }

    /**
     * Загрузчик лаунчера: классы пакета лаунчера берутся только из своего jar, без возврата к родителю. Копия лаунчера
     * на classpath (например, в тесте или по ошибке упаковки) не должна подменять версию слота, а jar без класса
     * лаунчера не запускается чужим.
     */
    private static final class SlotClassLoader extends URLClassLoader {
        SlotClassLoader(URL jar, ClassLoader parent) {
            super(new URL[] {jar}, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith("io.aequicor.heartbeat.launcher.")) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    loaded = findClass(name);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }
    }

    /**
     * Запускает {@code LauncherMain.start(args, applicationStarted, stager)} (или {@code start(args, applicationStarted)}
     * у лаунчера без обновления самого себя) из {@code jar} и возвращается, когда приложение
     * завершилось. {@code applicationStarted} вызывает лаунчер перед входом в приложение: это момент подтверждения слота.
     * {@code stager} принимает предложения нового jar лаунчера из поезда ({@code null} — предлагать некому).
     *
     * @throws NotStartedException если jar не открывается или в нём нет лаунчера; загрузчик закрыт, jar не занят
     * @throws Exception ошибка самого лаунчера или приложения после передачи управления (как её бросил {@code start})
     */
    static void launch(Path jar, String[] args, Instrumentation inst, Runnable applicationStarted,
                       Predicate<Map<String, String>> stager, boolean closeFixtureLoader) throws Exception {
        // URLClassLoader hides a jar that cannot be opened behind ClassNotFoundException; open it first to tell a
        // locked file (try again later) from a corrupt one (final)
        try (JarFile ignored = new JarFile(jar.toFile())) {
            // opened and closed: the jar is readable
        } catch (ZipException e) {
            throw new NotStartedException("Launcher " + jar + " is not a jar: " + e, e, true);
        } catch (IOException | SecurityException e) {
            throw new NotStartedException("Launcher " + jar + " cannot be opened now: " + e, e, false);
        }
        SlotClassLoader loader;
        try {
            loader = new SlotClassLoader(jar.toUri().toURL(), ClassLoader.getSystemClassLoader());
        } catch (IOException e) {
            throw new NotStartedException("Launcher " + jar + " has no valid address: " + e, e, true);
        }
        Method start;
        boolean withStager = true;
        try {
            Class<?> main = Class.forName(MAIN_CLASS, true, loader);
            try {
                start = main.getMethod("start", String[].class, Runnable.class, Predicate.class);
            } catch (NoSuchMethodException withoutStager) {
                // a launcher that does not take updates of itself still starts
                start = main.getMethod("start", String[].class, Runnable.class);
                withStager = false;
            }
            if (inst != null) {
                Class.forName(AGENT_CLASS, true, loader).getMethod("premain", String.class, Instrumentation.class)
                    .invoke(null, null, inst);
            }
        } catch (ReflectiveOperationException | LinkageError | SecurityException e) {
            closeQuietly(loader);
            throw new NotStartedException("Launcher " + jar + " cannot be started: " + e, e, true);
        }
        try {
            if (withStager) {
                start.invoke(null, args, applicationStarted, stager);
            } else {
                start.invoke(null, args, applicationStarted);
            }
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw e;
        } finally {
            // Production launchers can keep shutdown hooks and background work after start returns.
            // Synchronous test fixtures do not, and must release their jars before @TempDir cleanup.
            if (closeFixtureLoader) {
                closeQuietly(loader);
            }
        }
    }

    private static void closeQuietly(URLClassLoader loader) {
        try {
            loader.close();
        } catch (IOException e) {
            System.err.println("Class loader of a rejected launcher was not closed: " + e);
        }
    }
}
