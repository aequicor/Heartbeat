package io.aequicor.heartbeat.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LauncherStateTest {
    @TempDir
    Path tmp;

    Path file() {
        return tmp.resolve("state.properties");
    }

    Properties read() throws IOException {
        Properties props = new Properties();
        try (var in = Files.newInputStream(file())) {
            props.load(in);
        }
        return props;
    }

    @Test
    void stateWithoutFormatIsTheFirstFormat() throws IOException {
        Files.writeString(file(), "current=t1\nprevious=t0\ncrashes=1\n", StandardCharsets.UTF_8);
        LauncherState state = LauncherState.load(file());
        assertEquals("t1", state.current);
        assertEquals("t0", state.previous);
        assertEquals(1, state.crashes);
        assertTrue(state.unknown.isEmpty());
    }

    @Test
    void saveWritesTheFormat() throws IOException {
        LauncherState state = new LauncherState();
        state.current = "t1";
        state.save(file());
        assertEquals(Integer.toString(LauncherState.FORMAT), read().getProperty("format"));
    }

    @Test
    void stateOfANewerFormatIsRefusedAndLeftUntouched() throws IOException {
        String newer = "format=" + (LauncherState.FORMAT + 1) + "\ncurrent=t9\nslot=B\n";
        Files.writeString(file(), newer, StandardCharsets.UTF_8);
        assertThrows(LauncherState.UnsupportedFormatException.class, () -> LauncherState.load(file()));
        assertEquals(newer, Files.readString(file(), StandardCharsets.UTF_8));
    }

    @Test
    void unreadableFormatIsRefusedInsteadOfReadAsEmptyState() throws IOException {
        Files.writeString(file(), "format=abc\ncurrent=t1\n", StandardCharsets.UTF_8);
        assertThrows(LauncherState.UnsupportedFormatException.class, () -> LauncherState.load(file()));
        Files.writeString(file(), "format=0\ncurrent=t1\n", StandardCharsets.UTF_8);
        assertThrows(LauncherState.UnsupportedFormatException.class, () -> LauncherState.load(file()));
    }

    @Test
    void unknownFieldsSurviveARewrite() throws IOException {
        Files.writeString(file(), "format=1\ncurrent=t1\nslotHint=A\nextra=1\n", StandardCharsets.UTF_8);
        LauncherState state = LauncherState.load(file());
        assertEquals("A", state.unknown.get("slotHint"));
        state.current = "t2";
        state.save(file());
        Properties saved = read();
        assertEquals("t2", saved.getProperty("current"));
        assertEquals("A", saved.getProperty("slotHint"));
        assertEquals("1", saved.getProperty("extra"));
        assertFalse(saved.containsKey("unknown"));
    }

    @Test
    void corruptedFileStillStartsFromEmptyState() throws IOException {
        Files.writeString(file(), "current=\\uZZZZ\n", StandardCharsets.UTF_8);
        assertEquals("", LauncherState.load(file()).current);
    }
}
