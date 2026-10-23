package io.aequicor.heartbeat.launcher;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class LauncherMainTest {
    static String[] received;

    @SuppressWarnings("unused")
    public static void main(String[] args) {
        received = args;
    }

    @Test
    void invokesMainOfTheApplicationClass() throws Exception {
        LauncherMain.invokeMain(getClass().getClassLoader(), LauncherMainTest.class.getName(), new String[] {"a", "b"});
        assertArrayEquals(new String[] {"a", "b"}, received);
    }

    @Test
    void unknownClassFails() {
        assertThrows(ClassNotFoundException.class,
                () -> LauncherMain.invokeMain(getClass().getClassLoader(), "no.such.Main", new String[0]));
    }
}
