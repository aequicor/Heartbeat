package io.aequicor.heartbeat.selector;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

/**
 * Запускает лаунчер из jar (слота или встроенного) в его собственном загрузчике классов поверх системного. Классы
 * лаунчера не видны приложению и не затеняют друг друга, поэтому слот можно отвергнуть и запустить другой в том же
 * процессе. Лаунчер получает {@link Instrumentation} явно: его {@code LauncherAgent} при запуске через слот не
 * подключён как {@code -javaagent}.
 */
final class SlotLauncher {
    static final String AGENT_CLASS = "io.aequicor.heartbeat.launcher.LauncherAgent";
    static final String MAIN_CLASS = "io.aequicor.heartbeat.launcher.LauncherMain";

    /** Лаунчер не удалось подготовить: он ещё не выполнил ни строчки своей логики, можно запускать другой. */
    static final class NotStartedException extends Exception {
        NotStartedException(String message, Throwable cause) {
            super(message, cause);
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
     * Запускает {@code LauncherMain.start(args, applicationStarted)} из {@code jar} и возвращается, когда приложение
     * завершилось. {@code applicationStarted} вызывает лаунчер перед входом в приложение: это момент подтверждения слота.
     *
     * @throws NotStartedException если jar или класс лаунчера не открываются; лаунчер тогда не запускался
     * @throws Exception ошибка самого лаунчера или приложения после передачи управления (как её бросил {@code start})
     */
    static void launch(Path jar, String[] args, Instrumentation inst, Runnable applicationStarted) throws Exception {
        Method start;
        try {
            URLClassLoader loader = new SlotClassLoader(jar.toUri().toURL(), ClassLoader.getSystemClassLoader());
            if (inst != null) {
                Class.forName(AGENT_CLASS, true, loader).getMethod("premain", String.class, Instrumentation.class)
                    .invoke(null, null, inst);
            }
            start = Class.forName(MAIN_CLASS, true, loader).getMethod("start", String[].class, Runnable.class);
        } catch (IOException | ReflectiveOperationException | LinkageError | SecurityException e) {
            throw new NotStartedException("Launcher " + jar + " cannot be started: " + e, e);
        }
        try {
            start.invoke(null, args, applicationStarted);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw e;
        }
    }
}
