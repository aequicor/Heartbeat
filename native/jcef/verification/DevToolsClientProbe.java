package org.cef.browser;

import java.awt.Component;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.cef.CefClient;

/** Deterministic Java-only checks for the real patched client; never loads JNI or opens a window. */
public final class DevToolsClientProbe {
    public static void main(String[] args) throws Exception {
        responseOrder(true, true);
        responseOrder(false, true);
        responseOrder(true, false);
        responseOrder(false, false);
        closePending(false);
        closePending(true);
        synchronousResult();
        cancelPending(false, false);
        cancelPending(true, false);
        cancelPending(false, true);
        System.out.println("PASS DevToolsClientProbe: 10 deterministic callback-order checks");
    }

    private static void responseOrder(boolean resultFirst, boolean success) throws Exception {
        FakeBrowser browser = new FakeBrowser();
        CefDevToolsClient client = client(browser);
        CompletableFuture<String> response = client.executeDevToolsMethod("Runtime.evaluate", "{}");
        if (resultFirst) browser.respond(success);
        browser.id.complete(17);
        if (!resultFirst) browser.respond(success);
        verifyResult(response, success);
        require(queueSize(client) == 0, "Completed response retained in the pending map");
        client.close();
        System.out.println("PASS resultFirst=" + resultFirst + " success=" + success);
    }

    private static void synchronousResult() throws Exception {
        FakeBrowser browser = new FakeBrowser();
        browser.respondDuringSend = true;
        CefDevToolsClient client = client(browser);
        CompletableFuture<String> response = client.executeDevToolsMethod("Runtime.evaluate");
        verifyResult(response, true);
        require(queueSize(client) == 0, "Synchronous response retained in the pending map");
        client.close();
        System.out.println("PASS response before native execute returns");
    }

    private static void closePending(boolean idFirst) throws Exception {
        FakeBrowser browser = new FakeBrowser();
        CefDevToolsClient client = client(browser);
        CompletableFuture<String> response = client.executeDevToolsMethod("Runtime.evaluate");
        if (idFirst) browser.id.complete(17);
        client.close();
        verifyResult(response, false);
        browser.id.complete(17);
        browser.respond(true);
        browser.respond(false);
        require(queueSize(client) == 0, "Late callbacks repopulated a closed client's map");
        requireNoCancelledIds(client);
        verifyResult(client.executeDevToolsMethod("Runtime.evaluate"), false);
        System.out.println("PASS close idFirst=" + idFirst + " and late callbacks");
    }

    private static void verifyResult(CompletableFuture<String> response, boolean success) {
        require(response.isDone(), "Response stranded by callback ordering");
        if (success) {
            require("{\"value\":true}".equals(response.join()), "Wrong DevTools response");
        } else {
            require(response.isCompletedExceptionally(), "Failure/close must complete exceptionally");
            try {
                response.join();
                throw new AssertionError("Expected exceptional result");
            } catch (CompletionException expected) {
                require(expected.getCause() instanceof CefDevToolsClient.DevToolsException,
                    "Unexpected failure type: " + expected.getCause());
            }
        }
    }

    private static void cancelPending(boolean idFirst, boolean earlyResult) throws Exception {
        FakeBrowser browser = new FakeBrowser();
        CefDevToolsClient client = client(browser);
        CompletableFuture<String> cancelled = client.executeDevToolsMethod("Runtime.evaluate");
        CompletableFuture<String> neighbour = client.executeDevToolsMethod("Runtime.evaluate");
        if (idFirst) browser.id.complete(17);
        if (earlyResult) browser.respond(true);
        require(cancelled.cancel(true), "Cancellation must win before the response is delivered");
        browser.id.complete(17);
        require(queueSize(client) == 0, "Cancellation retained the known response entry");
        require(cancelled.isCancelled(), "Cancelled operation changed its terminal result");
        if (!earlyResult) browser.respond(true);
        require(queueSize(client) == 0, "Late result recreated a cancelled response entry");
        requireNoCancelledIds(client);
        require(!neighbour.isDone(), "Cancellation affected an independent operation");
        browser.secondId.complete(18);
        browser.observer.onDevToolsMethodResult(browser, 18, true, "{\"value\":true}");
        verifyResult(neighbour, true);
        require(queueSize(client) == 0, "Independent operation leaked its response");
        client.close();
        System.out.println("PASS cancel idFirst=" + idFirst + " earlyResult=" + earlyResult);
    }

    private static int queueSize(CefDevToolsClient client) throws Exception {
        Field field = CefDevToolsClient.class.getDeclaredField("queuedCommands_");
        field.setAccessible(true);
        return ((Map<?, ?>) field.get(client)).size();
    }

    private static CefDevToolsClient client(FakeBrowser browser) throws Exception {
        CefDevToolsClient client = new CefDevToolsClient(browser);
        Field field = CefDevToolsClient.class.getDeclaredField("queuedCommands_");
        field.setAccessible(true);
        browser.rendezvousLock = field.get(client);
        return client;
    }

    private static void requireNoCancelledIds(CefDevToolsClient client) throws Exception {
        Field field = CefDevToolsClient.class.getDeclaredField("cancelledMessageIds_");
        field.setAccessible(true);
        require(((Set<?>) field.get(client)).isEmpty(), "Completed callbacks retained cancellation tombstones");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class FakeBrowser extends CefBrowser_N {
        final CompletableFuture<Integer> id = new CompletableFuture<>();
        final CompletableFuture<Integer> secondId = new CompletableFuture<>();
        CefDevToolsMessageObserver observer;
        boolean respondDuringSend;
        int sends;
        Object rendezvousLock;

        FakeBrowser() { super(null, "about:blank", null, null, null, null); }

        @Override CompletableFuture<Integer> executeDevToolsMethod(String method, String params) {
            require(!Thread.holdsLock(rendezvousLock), "JNI execute entered under the rendezvous lock");
            if (++sends == 2) return secondId;
            if (respondDuringSend) {
                respond(true);
                id.complete(17);
            }
            return id;
        }

        @Override CefRegistration addDevToolsMessageObserver(CefDevToolsMessageObserver value) {
            observer = value;
            return new CefRegistration() { @Override public void dispose() {} };
        }

        void respond(boolean success) {
            observer.onDevToolsMethodResult(this, 17, success, "{\"value\":true}");
        }

        @Override public void createImmediately() { throw new AssertionError("JNI forbidden"); }
        @Override public Component getUIComponent() { throw new AssertionError("UI forbidden"); }
        @Override public CompletableFuture<BufferedImage> createScreenshot(boolean nativeResolution) {
            throw new AssertionError("Capture forbidden");
        }
        @Override protected CefBrowser_N createDevToolsBrowser(CefClient client, String url,
                CefRequestContext context, CefBrowser_N parent, Point inspectAt) {
            throw new AssertionError("JNI forbidden");
        }
    }
}
