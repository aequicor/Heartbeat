package io.aequicor.heartbeat.launcher;

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

/** Протокол {@link LaunchTimeline}: формат строки, выключение, экранирование, удержание файлов. */
class LaunchTimelineTest {
    @TempDir
    Path temp;

    private final String originalDir = System.getProperty(LaunchTimeline.DIR_PROPERTY);
    private final String originalEnabled = System.getProperty(LaunchTimeline.TIMELINE_PROPERTY);

    @AfterEach
    void restoreProperties() {
        restore(LaunchTimeline.DIR_PROPERTY, originalDir);
        restore(LaunchTimeline.TIMELINE_PROPERTY, originalEnabled);
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private Path timelineFile() throws IOException {
        try (Stream<Path> stream = Files.list(temp.resolve("logs"))) {
            List<Path> files = stream.filter(path -> path.getFileName().toString().startsWith("launch-timeline-"))
                .toList();
            assertEquals(1, files.size());
            return files.get(0);
        }
    }

    @Test
    void markAppendsJsonLineWithStageAndElapsed() throws IOException {
        System.setProperty(LaunchTimeline.DIR_PROPERTY, temp.toString());

        LaunchTimeline.mark("launcher.start");
        LaunchTimeline.mark("launcher.train-prepared");

        List<String> lines = Files.readAllLines(timelineFile());
        assertEquals(2, lines.size());
        String first = lines.get(0);
        assertTrue(first.startsWith("{\"launch\":\""), first);
        assertTrue(first.contains("\"stage\":\"launcher.start\""), first);
        assertTrue(first.contains("\"t\":"), first);
        assertTrue(first.contains("\"wall\":"), first);
        String launch = first.substring(first.indexOf("\"launch\":\"") + 10, first.indexOf("\",\"stage\""));
        assertTrue(lines.get(1).contains("\"launch\":\"" + launch + "\""), lines.get(1));
        long t0 = elapsedOf(first);
        long t1 = elapsedOf(lines.get(1));
        assertTrue(t1 >= t0, "elapsed must not decrease");
    }

    private static long elapsedOf(String line) {
        String marker = "\"t\":";
        int from = line.indexOf(marker) + marker.length();
        int to = line.indexOf(',', from);
        return Long.parseLong(line.substring(from, to));
    }

    @Test
    void offDisablesWrites() throws IOException {
        System.setProperty(LaunchTimeline.DIR_PROPERTY, temp.toString());
        System.setProperty(LaunchTimeline.TIMELINE_PROPERTY, "off");

        LaunchTimeline.mark("launcher.start");

        assertFalse(Files.exists(temp.resolve("logs")));
    }

    @Test
    void specialCharactersAreEscaped() throws IOException {
        System.setProperty(LaunchTimeline.DIR_PROPERTY, temp.toString());

        LaunchTimeline.mark("quo\"te\\slash\nnewline\ttab\u0001control");

        String line = Files.readAllLines(timelineFile()).get(0);
        assertTrue(line.contains("quo\\\"te\\\\slash\\nnewline\\ttab\\u0001control"), line);
    }

    @Test
    void expiredFilesArePrunedOnFirstMark() throws IOException {
        System.setProperty(LaunchTimeline.DIR_PROPERTY, temp.toString());
        Path logs = Files.createDirectories(temp.resolve("logs"));
        Path old = logs.resolve("launch-timeline-old@1.jsonl");
        Files.writeString(old, "{}\n");
        long expired = System.currentTimeMillis() - LaunchTimeline.RETENTION_MILLIS - 60_000;
        Files.setLastModifiedTime(old, java.nio.file.attribute.FileTime.fromMillis(expired));

        LaunchTimeline.mark("launcher.start");

        assertFalse(Files.exists(old));
        assertTrue(Files.exists(logs.resolve(Files.list(logs)
            .filter(path -> !path.equals(old)).findFirst().orElseThrow().getFileName())));
    }
}
