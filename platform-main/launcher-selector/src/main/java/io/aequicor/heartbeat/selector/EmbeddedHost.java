package io.aequicor.heartbeat.selector;

import java.awt.Frame;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.FileVisitResult;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Optional, versioned native-window transport. The native launcher registers these methods before invoking
 * SelectorMain. No slot or train decision depends on this transport. A jpackage launch has no registered host.
 * All window methods are called on the AWT event thread by the selected application, after its storage guards.
 */
public final class EmbeddedHost {
    private EmbeddedHost() {}

    /** Zero for the ordinary jpackage launcher, one for the native Windows host ABI. */
    public static int version() {
        try {
            return nativeVersion();
        } catch (UnsatisfiedLinkError unavailable) {
            return 0;
        }
    }

    /** System.exit in an older train or during cancellation also terminates the native parent in this mode. */
    public static void installCleanup() {
        String value = System.getenv("HEARTBEAT_HANDOFF_DIR");
        if (value == null || value.isBlank()) return;
        Path directory = Path.of(value);
        String ownedPrefix = ProcessHandle.current().pid() + "-";
        if (!directory.isAbsolute() || directory.getFileName() == null ||
                !directory.getFileName().toString().startsWith(ownedPrefix)) {
            throw new IllegalArgumentException("Embedded handoff directory is not owned by this process");
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                cleanupHandoff(directory);
            } catch (IOException | RuntimeException error) {
                System.err.println("Embedded handoff cleanup failed: " + error);
            }
        }, "heartbeat-embedded-handoff-cleanup"));
    }

    static void cleanupHandoff(Path directory) throws IOException {
        if (!Files.exists(directory, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        // No FOLLOW_LINKS: a marker replaced by a symlink never grants ownership of its target.
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path path, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.deleteIfExists(path);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static native int nativeVersion();
    public static native Frame createFrame();
    public static native long handle();
    public static native long childHandle();
    public static native boolean isRevealed();
    public static native void abandon();
    public static native void attachChild(long handle);
    public static native void captionClient(boolean client);
    public static native void synchronize(Frame frame);
    public static native boolean takeCloseRequest();
    public static native void present();
}
