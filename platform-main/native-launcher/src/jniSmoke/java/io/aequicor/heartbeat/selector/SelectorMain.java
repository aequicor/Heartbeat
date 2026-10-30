package io.aequicor.heartbeat.selector;

import java.awt.Frame;
import java.awt.EventQueue;

/** Isolated JNI fixture; the production selector is tested independently and is not on this classpath. */
public final class SelectorMain {
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !"Привет 世界".equals(args[0])) throw new AssertionError("UTF-16 arguments lost");
        if (!"ok".equals(System.getProperty("heartbeat.jni.smoke"))) throw new AssertionError("JVM options lost");
        if (EmbeddedHost.version() != 1 || EmbeddedHost.handle() == 0) throw new AssertionError("Missing host");
        EventQueue.invokeAndWait(() -> {
            Frame frame = EmbeddedHost.createFrame();
            try {
                try {
                    EmbeddedHost.attachChild(0);
                    throw new AssertionError("Invalid peer accepted");
                } catch (IllegalStateException expected) {
                    // A rejected peer must not count as a successful attachment.
                }
                try {
                    EmbeddedHost.present();
                    throw new AssertionError("Unattached peer presented");
                } catch (IllegalStateException expected) {
                    if (EmbeddedHost.isRevealed()) throw new AssertionError("Failed present revealed content");
                }
                long child = EmbeddedHost.childHandle();
                if (child == 0 || child == EmbeddedHost.handle()) throw new AssertionError("No child peer");
                EmbeddedHost.attachChild(child);
                EmbeddedHost.synchronize(frame);
                frame.setVisible(true);
                if (!frame.isDisplayable()) throw new AssertionError("No native peer");
                EmbeddedHost.present();
                if (EmbeddedHost.takeCloseRequest()) throw new AssertionError("Unexpected close request");
            } finally {
                frame.dispose();
            }
        });
    }
}
