package io.aequicor.heartbeat.selector;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.aequicor.heartbeat.launcher.LauncherPathsBridge;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Селектор и лаунчер должны вычислять один корень: слоты лежат в корне лаунчера. */
class SelectorPathsParityTest {
    @Test
    void userDataDirMatchesTheLauncherOnEveryOs() {
        String[][] cases = {
            {"Windows 11", "C:\\Users\\a", "C:\\Users\\a\\AppData\\Roaming", null, null},
            {"Windows 11", "C:\\Users\\a", null, null, "Heartbeat-Beta"},
            {"Mac OS X", "/Users/a", null, null, null},
            {"Mac OS X", "/Users/a", null, null, "bad name!"},
            {"Linux", "/home/a", null, null, null},
            {"Linux", "/home/a", null, "/data/share", "HeartbeatE2E"},
            {"Linux", "/home/a", null, "relative/share", null},
        };
        for (String[] c : cases) {
            Path expected = LauncherPathsBridge.userDataDir(c[0], c[1], c[2], c[3], c[4]);
            assertEquals(expected, SelectorPaths.userDataDir(c[0], c[1], c[2], c[3], c[4]), String.join("|",
                String.valueOf(c[0]), String.valueOf(c[4])));
            assertEquals(LauncherPathsBridge.launcherRootBeside(expected), SelectorPaths.launcherRootBeside(expected));
        }
    }
}
