package io.aequicor.heartbeat.selector;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class NativeCancellationTest {
    @TempDir Path directory;

    @Test
    void cancellationWaitsForCleanExitHookAndRunsOnce() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (NativeCancellation watcher = new NativeCancellation(directory, calls::incrementAndGet)) {
            Files.writeString(directory.resolve("cancel"), "cancel");
            watcher.poll();
            assertEquals(0, calls.get());
            watcher.enable();
            watcher.poll();
            watcher.poll();
            assertEquals(1, calls.get());
        }
    }

    @Test
    void hiddenWindowEndsCancellationOwnership() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (NativeCancellation watcher = new NativeCancellation(directory, calls::incrementAndGet)) {
            watcher.enable();
            Files.writeString(directory.resolve("hidden"), "hidden");
            Files.writeString(directory.resolve("cancel"), "cancel");
            watcher.poll();
            assertEquals(0, calls.get());
        }
    }

    @Test
    void closedWatcherCannotCancelApplication() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        NativeCancellation watcher = new NativeCancellation(directory, calls::incrementAndGet);
        watcher.enable();
        watcher.close();
        Files.writeString(directory.resolve("cancel"), "cancel");
        watcher.poll();
        assertEquals(0, calls.get());
    }
    @Test
    @Timeout(10)
    void selectorCancellationRunsTheCleanExitShutdownHook() throws Exception {
        Path result = directory.resolve("result");
        String classpath = Path.of(NativeCancellation.class.getProtectionDomain().getCodeSource().getLocation().toURI())
            + java.io.File.pathSeparator
            + Path.of(Probe.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        ProcessBuilder builder = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Dheartbeat.launch.timeline=off", "-cp", classpath, Probe.class.getName(), result.toString());
        builder.environment().put("HEARTBEAT_HANDOFF_DIR", directory.toString());
        Process process = builder.redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try {
            assertEquals("ready", process.inputReader().readLine());
            Files.writeString(directory.resolve("cancel"), "cancel");
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
            assertEquals("true", Files.readString(result));
        } finally {
            process.destroyForcibly();
        }
    }

    public static class Probe {
        public static void main(String[] args) throws Exception {
            Path result = Path.of(args[0]);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    Files.writeString(result, System.getProperty("heartbeat.exit.clean", "false"));
                } catch (java.io.IOException failure) {
                    throw new java.io.UncheckedIOException(failure);
                }
            }));
            try (NativeCancellation watcher = NativeCancellation.watch(new String[0])) {
                watcher.enable();
                System.out.println("ready");
                new java.util.concurrent.CountDownLatch(1).await();
            }
        }
    }

}
