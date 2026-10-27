package io.aequicor.heartbeat.launcher;

import java.nio.file.Path;

/** Доступ теста селектора к правилам путей лаунчера (они package-private): проверяется их совпадение. */
public final class LauncherPathsBridge {
    private LauncherPathsBridge() {
    }

    public static Path userDataDir(String os, String home, String appData, String xdgDataHome, String dataName) {
        return LauncherMain.userDataDir(os, home, appData, xdgDataHome, dataName);
    }

    public static Path launcherRootBeside(Path dataDir) {
        return LauncherMain.launcherRootBeside(dataDir);
    }
}
