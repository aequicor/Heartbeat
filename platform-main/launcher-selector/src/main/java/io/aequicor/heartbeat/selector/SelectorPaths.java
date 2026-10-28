package io.aequicor.heartbeat.selector;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Где лежат слоты: {@code <корень лаунчера>/slots}. Корень лаунчера определяется так же, как в {@code LauncherMain}
 * лаунчера: свойство {@code heartbeat.launcher.root} либо, с {@code heartbeat.launcher.default.root=true}, каталог рядом
 * с данными пользователя ОС. Правила дублируются, потому что селектор запускается раньше лаунчера; совпадение проверяет
 * тест паритета.
 */
final class SelectorPaths {
    static final String ROOT = "heartbeat.launcher.root";
    static final String DEFAULT_ROOT = "heartbeat.launcher.default.root";
    static final String DATA_DIR = "heartbeat.data.dir";
    static final String DATA_NAME = "heartbeat.data.name";

    private SelectorPaths() {
    }

    /** Каталог слотов по системным свойствам процесса. */
    static Path slotsDir() {
        String rootValue = System.getProperty(ROOT);
        Path root;
        if (rootValue != null) {
            root = Path.of(rootValue);
        } else if (Boolean.getBoolean(DEFAULT_ROOT)) {
            Path dataDir = Path.of(System.getProperty(DATA_DIR, userDataDir(System.getProperty("os.name", ""),
                System.getProperty("user.home", ""), System.getenv("APPDATA"), System.getenv("XDG_DATA_HOME"),
                System.getProperty(DATA_NAME)).toString()));
            root = launcherRootBeside(dataDir);
        } else {
            throw new IllegalStateException("System property " + ROOT + " is required");
        }
        return root.resolve("slots");
    }

    /** Каталог данных приложения; повторяет {@code LauncherMain.userDataDir}. */
    static Path userDataDir(String os, String home, String appData, String xdgDataHome, String dataName) {
        String name = dataName != null && dataName.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,31}") ? dataName : "Heartbeat";
        if (os.toLowerCase(Locale.ROOT).startsWith("windows")) {
            Path base = appData != null ? Path.of(appData) : Path.of(home, "AppData", "Roaming");
            return base.resolve("Aequicor").resolve(name);
        }
        if (os.toLowerCase(Locale.ROOT).startsWith("mac")) {
            return Path.of(home, "Library", "Application Support", name);
        }
        Path dataHome = xdgDataHome != null && !xdgDataHome.isBlank() && Path.of(xdgDataHome).isAbsolute()
            ? Path.of(xdgDataHome) : Path.of(home, ".local", "share");
        return dataHome.resolve(name.toLowerCase(Locale.ROOT));
    }

    /** Корень лаунчера рядом с каталогом данных, вне его; повторяет {@code LauncherMain.launcherRootBeside}. */
    static Path launcherRootBeside(Path dataDir) {
        return dataDir.resolveSibling(dataDir.getFileName() + "-launcher");
    }
}
