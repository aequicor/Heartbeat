package io.aequicor.heartbeat.selector;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cancellation handshake with the native parent, before its window is hidden. Cancellation is enabled only after
 * the selected launcher has installed its clean-exit hook and confirmed its slot. No train state is read here.
 */
final class NativeCancellation implements AutoCloseable {
    private final Path directory;
    private final Runnable cancellation;
    private final AtomicBoolean finished = new AtomicBoolean();
    private volatile boolean enabled;
    private Thread watcher;

    NativeCancellation(Path directory, Runnable cancellation) {
        this.directory = directory;
        this.cancellation = cancellation;
    }

    static NativeCancellation watch(String[] args) {
        String value = System.getenv("HEARTBEAT_HANDOFF_DIR");
        Path directory = value == null || value.isBlank() || SelectorMain.isAuxiliary(args) ? null : Path.of(value);
        if (directory != null && !directory.isAbsolute()) {
            throw new IllegalArgumentException("Native handoff directory must be absolute");
        }
        NativeCancellation result = new NativeCancellation(directory, () -> {
            System.setProperty("heartbeat.exit.clean", "true");
            LaunchMarks.mark("selector.native-cancelled");
            System.exit(0);
        });
        if (directory != null) {
            result.watcher = new Thread(result::watchLoop, "heartbeat-native-cancel");
            result.watcher.setDaemon(true);
            result.watcher.start();
        }
        return result;
    }

    void enable() {
        enabled = true;
    }

    void poll() {
        if (directory == null || finished.get()) return;
        if (Files.exists(directory.resolve("hidden"))) {
            finished.set(true);
        } else if (enabled && Files.exists(directory.resolve("cancel")) && finished.compareAndSet(false, true)) {
            cancellation.run();
        }
    }

    private void watchLoop() {
        while (!finished.get()) {
            poll();
            try {
                Thread.sleep(50);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Override
    public void close() {
        finished.set(true);
        if (watcher != null) watcher.interrupt();
    }
}
