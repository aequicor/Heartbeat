package io.aequicor.heartbeat.selector;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Проверка {@link LaunchMarks}: формат строки совпадает с протоколом лаунчера, выключение работает. */
class LaunchMarksTest {
    @TempDir
    Path temp;

    private final String originalDir = System.getProperty(LaunchMarks.DIR_PROPERTY);
    private final String originalEnabled = System.getProperty(LaunchMarks.TIMELINE_PROPERTY);

    @AfterEach
    void restoreProperties() {
        restore(LaunchMarks.DIR_PROPERTY, originalDir);
        restore(LaunchMarks.TIMELINE_PROPERTY, originalEnabled);
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    @Test
    void markWritesTheSharedLauncherProtocolLine() throws IOException {
        System.setProperty(LaunchMarks.DIR_PROPERTY, temp.toString());

        LaunchMarks.mark("selector.main");

        try (Stream<Path> stream = Files.list(temp.resolve("logs"))) {
            List<Path> files = stream
                .filter(path -> path.getFileName().toString().startsWith("launch-timeline-"))
                .toList();
            assertEquals(1, files.size());
            String line = Files.readAllLines(files.get(0)).get(0);
            assertTrue(line.startsWith("{\"launch\":\""), line);
            assertTrue(line.contains("\"stage\":\"selector.main\""), line);
            assertTrue(line.contains("\"t\":"), line);
            assertTrue(line.contains("\"wall\":"), line);
        }
    }

    @Test
    void offDisablesWrites() throws IOException {
        System.setProperty(LaunchMarks.DIR_PROPERTY, temp.toString());
        System.setProperty(LaunchMarks.TIMELINE_PROPERTY, "off");

        LaunchMarks.mark("selector.main");

        assertFalse(Files.exists(temp.resolve("logs")));
    }
}
