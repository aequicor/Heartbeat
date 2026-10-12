import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.CefRequestContextSettings;
import org.cef.CefSettings;
import org.cef.SystemBootstrap;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.browser.CefRequestContext;
import org.cef.handler.CefAppHandlerAdapter;
import org.cef.handler.CefLifeSpanHandlerAdapter;
import org.cef.handler.CefLoadHandler;
import org.cef.handler.CefLoadHandlerAdapter;

/** Real windowed JCEF test process. Run through run_probe.py; never uses a user's profile. */
public final class SessionProbe {
    private static final long TIMEOUT_SECONDS = 60;
    private final Map<String, String> arguments;
    private final List<Map<String, Object>> checks = new ArrayList<>();
    private final List<Tab> tabs = new ArrayList<>();
    private final List<Anchor> anchors = new ArrayList<>();
    private final CompletableFuture<Void> terminated = new CompletableFuture<>();
    private final List<Throwable> uncaught = new CopyOnWriteArrayList<>();
    private CefApp app;

    private SessionProbe(Map<String, String> arguments) { this.arguments = arguments; }

    public static void main(String[] args) throws Exception {
        Map<String, String> arguments = new LinkedHashMap<>();
        for (String arg : args) {
            int separator = arg.indexOf('=');
            if (!arg.startsWith("--") || separator < 3) throw new IllegalArgumentException(arg);
            arguments.put(arg.substring(2, separator), arg.substring(separator + 1));
        }
        SessionProbe probe = new SessionProbe(arguments);
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            System.err.println("UNCAUGHT on " + thread.getName() + ": " + failure);
            failure.printStackTrace(System.err);
            probe.uncaught.add(failure);
        });
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("phase", probe.required("mode"));
        report.put("pid", ProcessHandle.current().pid());
        report.put("java", System.getProperty("java.version"));
        report.put("os", System.getProperty("os.name"));
        report.put("architecture", System.getProperty("os.arch"));
        report.put("requestedScale", arguments.getOrDefault("scale", "native"));
        int exitCode = 0;
        try {
            probe.start();
            probe.run();
            require(probe.uncaught.isEmpty(), "Uncaught native callback failures: " + probe.uncaught);
        } catch (Throwable failure) {
            failure.printStackTrace(System.err);
            report.put("error", failure.toString());
            exitCode = 1;
        } finally {
            try { probe.close(); }
            catch (Throwable failure) {
                failure.printStackTrace(System.err);
                report.put("shutdownError", failure.toString());
                exitCode = 1;
            }
            report.put("checks", probe.checks);
            report.put("passed", exitCode == 0);
            Files.writeString(Path.of(probe.required("result")), ProbeJson.encode(report), StandardCharsets.UTF_8);
        }
        // Exit only after CEF TERMINATED. Python rejects a missing/failed report or nonzero exit.
        System.exit(exitCode);
    }

    private String required(String name) {
        return Objects.requireNonNull(arguments.get(name), "Missing --" + name);
    }

    private void start() throws Exception {
        require(!GraphicsEnvironment.isHeadless(), "A real desktop session is required");
        Path nativeDirectory = Path.of(required("native-dir")).toAbsolutePath();
        Path profileRoot = Path.of(required("profiles")).toAbsolutePath();
        Files.createDirectories(profileRoot);
        SystemBootstrap.setLoader(name -> {
            if (name.equals("jawt")) System.loadLibrary(name);
            else System.load(nativeDirectory.resolve(System.mapLibraryName(name)).toString());
        });
        List<String> cefArguments = new ArrayList<>(List.of(
            "--disable-extensions", "--disable-background-networking", "--disable-component-update"));
        if (arguments.containsKey("framework")) {
            cefArguments.add("--framework-dir-path=" + required("framework"));
        }
        if (arguments.containsKey("main-bundle")) {
            cefArguments.add("--main-bundle-path=" + required("main-bundle"));
        }
        String scale = arguments.getOrDefault("scale", "native");
        if (!scale.equals("native")) cefArguments.add("--force-device-scale-factor=" + scale);
        String[] commandLine = cefArguments.toArray(String[]::new);
        CefApp.addAppHandler(new CefAppHandlerAdapter(commandLine) {
            @Override public void stateHasChanged(CefApp.CefAppState state) {
                if (state == CefApp.CefAppState.TERMINATED) terminated.complete(null);
            }
        });
        require(CefApp.startup(commandLine), "CefApp.startup failed");
        CefSettings settings = new CefSettings();
        settings.root_cache_path = profileRoot.toString();
        settings.cache_path = "";
        settings.persist_session_cookies = false;
        settings.windowless_rendering_enabled = false;
        settings.browser_subprocess_path = required("helper");
        settings.resources_dir_path = required("resources");
        settings.locales_dir_path = required("locales");
        settings.log_severity = CefSettings.LogSeverity.LOGSEVERITY_WARNING;
        settings.log_file = required("result") + ".cef.log";
        app = CefApp.getInstance(commandLine, settings);
        CefClient bootstrap = app.createClient();
        bootstrap.dispose();
        require(CefApp.getState() == CefApp.CefAppState.INITIALIZED, "CEF did not initialize");
        passed("native-startup", Map.of("api", CefApp.class.getProtectionDomain().getCodeSource().toString()));
    }

    private void run() throws Exception {
        switch (required("mode")) {
            case "write" -> write();
            case "read" -> read();
            case "clear", "delete" -> removeProfile(required("mode"));
            case "visual" -> visual();
            case "visibility-close" -> visibilityClose();
            case "anchor-close" -> anchorClose();
            default -> throw new IllegalArgumentException("Unknown mode");
        }
    }

    private Anchor anchor(String profile) throws Exception {
        // Chrome CEF152 requires cache_path to be root_cache_path or its immediate child.
        Path path = Path.of(required("profiles"), profile).toAbsolutePath();
        Files.createDirectories(path);
        CefRequestContextSettings settings = new CefRequestContextSettings();
        settings.cache_path = path.toString();
        settings.persist_session_cookies = true;
        Anchor anchor = new Anchor(onEdt(() -> CefRequestContext.createContext(settings, null)));
        require(anchor.context != null && !anchor.context.isGlobal(), "Persistent anchor unavailable");
        anchors.add(anchor);
        return anchor;
    }

    private Tab tab(Anchor anchor, String name) throws Exception {
        Tab result = new Tab(anchor, name);
        tabs.add(result);
        await(result.loaded, "load " + name);
        result.assertSingleCreation();
        require(Boolean.TRUE.equals(result.evaluate("Boolean(window.probe)")), "Fixture missing");
        return result;
    }

    private void write() throws Exception {
        Anchor a = anchor("profile-a");
        Anchor b = anchor("profile-b");
        Tab a1 = tab(a, "a1");
        Tab a2 = tab(a, "a2");
        Tab b1 = tab(b, "b1");
        storage(a1, null, "new-profile-a-empty");
        storage(b1, null, "new-profile-b-empty");
        a1.write(required("marker-a"));
        storage(a2, required("marker-a"), "tabs-share-all-site-data");
        storage(b1, null, "profile-b-isolated-from-a");
        ProbeDownload.verify(a.context, b.context, required("origin"), required("server-token"), required("marker-a"));
        passed("context-url-request-cookies-isolation-and-cancel", Map.of());
        b1.write(required("marker-b"));
        storage(a1, required("marker-a"), "profile-a-isolated-from-b");
        a1.close();
        storage(a2, required("marker-a"), "closing-one-tab-preserves-other");
        // A new wrapper still shares the anchor after another tab disposed its own wrapper.
        Tab a3 = tab(a, "a3");
        storage(a3, required("marker-a"), "new-tab-shares-surviving-anchor");
        a2.close(); a3.close(); a.close();
        storage(b1, required("marker-b"), "closing-profile-preserves-other-profile");
        b1.close(); b.close();
    }

    private void read() throws Exception {
        Anchor a = anchor("profile-a");
        Anchor b = anchor("profile-b");
        Tab a1 = tab(a, "restart-a");
        Tab b1 = tab(b, "restart-b");
        String expectedA = required("marker-a");
        storage(a1, expectedA.equals("EMPTY") ? null : expectedA, "profile-a-after-process-restart");
        storage(b1, required("marker-b"), "profile-b-after-process-restart");
    }

    private void removeProfile(String mode) throws Exception {
        Anchor a = anchor("profile-a");
        Anchor b = anchor("profile-b");
        Tab a1 = tab(a, mode + "-a");
        Tab b1 = tab(b, mode + "-b");
        storage(a1, required("marker-a"), "data-present-before-" + mode);
        a1.close();
        a.close(); // Callback is the barrier: deletion must work while CefApp and profile B remain live.
        HttpRequest request = HttpRequest.newBuilder(URI.create(required("origin") + "/" + mode + "-profile"))
            .header("X-Probe-Token", required("server-token"))
            .timeout(Duration.ofSeconds(TIMEOUT_SECONDS)).POST(HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<String> response;
        try (HttpClient client = HttpClient.newHttpClient()) {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        }
        require(response.statusCode() == 200, mode + " after disposal failed: " + response.body());
        Anchor reopened = anchor("profile-a");
        Tab blank = tab(reopened, mode + "-reopened");
        storage(blank, null, "all-profile-data-empty-after-" + mode);
        storage(b1, required("marker-b"), "other-profile-unaffected-by-" + mode);
        if (mode.equals("clear")) {
            blank.write(required("marker-next"));
            storage(blank, required("marker-next"), "cleared-profile-reusable");
        }
    }

    private void storage(Tab tab, String expected, String name) throws Exception {
        Map<?, ?> state = object(tab.evaluate("window.probe.read()"));
        Map<?, ?> cookies = object(state.get("cookies"));
        for (String key : List.of("local", "idb", "cache")) {
            require(Objects.equals(expected, state.get(key)), name + ": " + key + " mismatch");
        }
        for (String key : List.of("hb_session", "hb_persistent")) {
            require(Objects.equals(expected, cookies.get(key)), name + ": " + key + " mismatch");
        }
        passed(name, Map.of("sessionCookie", true, "persistentCookie", true,
            "localStorage", true, "indexedDB", true, "cacheStorage", true));
    }

    private void visual() throws Exception {
        Tab tab = tab(anchor("profile-b"), "visual");
        tab.evaluate("new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve(true))))");
        Map<?, ?> geometry = object(tab.evaluate("window.probe.geometry()"));
        double dpr = number(geometry, "dpr");
        String requestedScale = arguments.getOrDefault("scale", "native");
        if (!requestedScale.equals("native")) {
            require(Math.abs(dpr - Double.parseDouble(requestedScale)) < 0.05,
                "Chromium did not apply the requested device scale factor");
        }
        double x = number(geometry, "x") + number(geometry, "targetWidth") / 2;
        double y = number(geometry, "y") + number(geometry, "targetHeight") / 2;
        Map<?, ?> capture = tab.cdp("Page.captureScreenshot", Map.of("format", "png",
            "fromSurface", true, "captureBeyondViewport", false));
        byte[] bytes = Base64.getDecoder().decode((String) capture.get("data"));
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        require(image != null, "Screenshot was not a PNG");
        require(Math.abs(image.getWidth() - number(geometry, "width") * dpr) <= 2,
            "Screenshot width does not match CSS viewport times devicePixelRatio");
        require(Math.abs(image.getHeight() - number(geometry, "height") * dpr) <= 2,
            "Screenshot height does not match CSS viewport times devicePixelRatio");
        int sampleX = (int) Math.round((number(geometry, "x") + 8) * dpr);
        int sampleY = (int) Math.round((number(geometry, "y") + 8) * dpr);
        require((image.getRGB(sampleX, sampleY) & 0xffffff) == 0x116fdd,
            "Screenshot target pixel is not at its expected scaled coordinate");
        Files.write(Path.of(required("result") + ".png"), bytes);
        tab.cdp("Input.dispatchMouseEvent", Map.of("type", "mouseMoved", "x", x, "y", y));
        try {
            tab.cdp("Input.dispatchMouseEvent", Map.of("type", "mousePressed", "x", x, "y", y,
                "button", "left", "buttons", 1, "clickCount", 1));
        } finally {
            tab.cdp("Input.dispatchMouseEvent", Map.of("type", "mouseReleased", "x", x, "y", y,
                "button", "left", "buttons", 0, "clickCount", 1));
        }
        Map<?, ?> click = object(tab.evaluate("window.probe.nextClick"));
        require(Boolean.TRUE.equals(click.get("trusted")), "Click did not come from native input");
        require(Math.abs(number(click, "x") - x) <= 1 && Math.abs(number(click, "y") - y) <= 1,
            "Input coordinates differ from screenshot CSS geometry");
        double awtScale = onEdt(() -> tab.frame.getGraphicsConfiguration().getDefaultTransform().getScaleX());
        passed("screenshot-and-native-input", Map.of("geometry", geometry, "awtScale", awtScale,
            "pngWidth", image.getWidth(), "pngHeight", image.getHeight(),
            "scaleMode", arguments.getOrDefault("scale", "native")));
    }

    private void visibilityClose() throws Exception {
        final int iterations = 20;
        final int visibilityPairs = 64;
        Anchor anchor = anchor("profile-visibility");
        Tab survivor = tab(anchor, "visibility-survivor");
        survivor.evaluate("window.visibilitySentinel = 'retained'");
        for (int index = 0; index < iterations; index++) {
            Tab closing = tab(anchor, "visibility-closing-" + index);
            closing.closeWithPendingVisibility(visibilityPairs);
            require("retained".equals(survivor.evaluate("window.visibilitySentinel")),
                "Closing another native view disturbed the surviving tab");
            require(remainingClients() == 1, "Closed tab retained a native client in iteration " + index);
            passed("visibility-close-iteration", Map.of("iteration", index, "visibilityPairs", visibilityPairs,
                "resizePaintCalls", visibilityPairs * 2));
        }
        survivor.close();
        anchor.close();
        require(remainingClients() == 0, "Visibility stress leaked clients");
        passed("queued-visibility-close-lifecycle", Map.of("freshTabs", iterations, "survivingTabs", 1));
    }

    private void anchorClose() throws Exception {
        final int iterations = 20;
        Anchor control = anchor("anchor-control");
        Tab survivor = tab(control, "anchor-control");
        survivor.evaluate("window.anchorSentinel = 'retained'");
        for (int index = 0; index < iterations; index++) {
            Anchor current = anchor("anchor-closing-" + index);
            Tab closing = tab(current, "anchor-closing-" + index);
            closing.write("teardown-" + index);
            closing.close();
            current.close();
            require(remainingClients() == 1, "Anchor teardown retained a client in iteration " + index);
            require("retained".equals(survivor.evaluate("window.anchorSentinel")),
                "Anchor teardown disturbed the control browser");
            passed("anchor-close-iteration", Map.of("iteration", index, "remainingClients", 1,
                "flushCompleted", true));
        }
        survivor.close();
        control.close();
        require(remainingClients() == 0, "Anchor teardown leaked clients");
        passed("persistent-anchor-close-lifecycle", Map.of("freshAnchors", iterations, "remainingClients", 0));
    }

    private void close() throws Exception {
        // Do not claim graceful shutdown if any close/flush callback times out.
        for (Tab tab : tabs) tab.close();
        for (Anchor anchor : anchors) anchor.close();
        if (app != null) {
            System.out.println("BEFORE APP DISPOSE state=" + CefApp.getState()
                + " remainingClients=" + remainingClients());
            onEdt(() -> { app.dispose(); return null; });
            await(terminated, "CEF TERMINATED");
            passed("graceful-cef-termination", Map.of());
        }
    }

    private int remainingClients() throws Exception {
        // Read-only diagnostics for the exact pinned API; never mutate JCEF's ownership registry.
        Field clients = CefApp.class.getDeclaredField("clients_");
        clients.setAccessible(true);
        synchronized (app) { return ((java.util.Set<?>) clients.get(app)).size(); }
    }

    private static int registeredBrowsers(CefClient client) throws Exception {
        Method browsers = CefClient.class.getDeclaredMethod("getAllBrowser");
        browsers.setAccessible(true);
        return ((Object[]) browsers.invoke(client)).length;
    }

    private final class Anchor {
        final CefRequestContext context;
        boolean closed;
        Anchor(CefRequestContext context) { this.context = context; }
        void close() throws Exception {
            if (closed) return;
            CompletableFuture<Void> flushed = new CompletableFuture<>();
            onEdt(() -> { context.dispose(() -> flushed.complete(null)); return null; });
            await(flushed, "anchor flush/release callback");
            closed = true;
            passed("anchor-flush-release", Map.of());
        }
    }

    private final class Tab {
        final CompletableFuture<Void> loaded = new CompletableFuture<>();
        final CompletableFuture<Void> closed = new CompletableFuture<>();
        final CefClient client;
        final CefBrowser browser;
        final JFrame frame;
        final List<Integer> createdIds = new CopyOnWriteArrayList<>();
        boolean closeRequested;

        Tab(Anchor anchor, String name) throws Exception {
            client = app.createClient();
            client.addLifeSpanHandler(new CefLifeSpanHandlerAdapter() {
                @Override public void onAfterCreated(CefBrowser browser) {
                    int identifier = browser.getIdentifier();
                    createdIds.add(identifier);
                    System.out.println("BROWSER CREATED tab=" + name + " id=" + identifier
                        + " javaIdentity=" + System.identityHashCode(browser) + " createdIds=" + createdIds);
                }
                @Override public void onAfterClose(CefBrowser browser) {
                    completeNativeClose(browser.getIdentifier());
                }
                @Override public void onCreationFailed(CefBrowser browser) {
                    closeRequested = true;
                    completeNativeClose(-1);
                    loaded.completeExceptionally(new AssertionError("Native browser creation failed: " + name));
                }
            });
            client.addLoadHandler(new CefLoadHandlerAdapter() {
                @Override public void onLoadEnd(CefBrowser browser, CefFrame frame, int status) {
                    if (!frame.isMain() || !frame.getURL().equals(required("origin") + "/fixture")) return;
                    if (status == 200) loaded.complete(null);
                    else loaded.completeExceptionally(new AssertionError("Fixture HTTP " + status));
                }
                @Override public void onLoadError(CefBrowser browser, CefFrame frame,
                    CefLoadHandler.ErrorCode code, String message, String url) {
                    if (frame.isMain()) loaded.completeExceptionally(new AssertionError("Load " + code + ": " + message));
                }
            });
            CefRequestContext shared = onEdt(() -> CefRequestContext.createContext(anchor.context, null));
            require(shared != null && shared != anchor.context, "Shared context must have its own wrapper");
            browser = onEdt(() -> client.createBrowser(required("origin") + "/fixture", false, false, shared));
            frame = onEdt(() -> {
                JFrame window = new JFrame("Heartbeat native verification: " + name);
                window.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
                Component surface = browser.getUIComponent();
                window.add(surface, BorderLayout.CENTER);
                window.setSize(800, 600);
                window.setVisible(true);
                browser.createImmediately();
                return window;
            });
        }

        private void assertSingleCreation() throws Exception {
            int count = registeredBrowsers(client);
            require(createdIds.size() == 1 && count == 1,
                "A Java browser must have exactly one native creation: createdIds=" + createdIds
                    + " registeredBrowsers=" + count);
        }

        private void completeNativeClose(int identifier) {
            try {
                // A diagnostic assertion of the public hook's contract, not a reflection-based barrier.
                int remaining = registeredBrowsers(client);
                require(remaining == 0, "After-close hook preceded registry cleanup: browser=" + identifier
                    + " registeredBrowsers=" + remaining);
                client.dispose();
                System.out.println("CLIENT DISPOSED browser=" + identifier
                    + " remainingClients=" + remainingClients());
                closed.complete(null);
            } catch (Throwable failure) {
                failure.printStackTrace(System.err);
                closed.completeExceptionally(failure);
            }
        }

        void write(String marker) throws Exception {
            require(Boolean.TRUE.equals(evaluate("window.probe.write(" + ProbeJson.encode(marker) + ")")),
                "Fixture write did not finish");
        }

        Map<?, ?> cdp(String method, Map<String, Object> params) throws Exception {
            CompletableFuture<String> future = onEdt(() -> browser.getDevToolsClient()
                .executeDevToolsMethod(method, ProbeJson.encode(params)));
            Map<?, ?> result = object(ProbeJson.parse(await(future, method)));
            require(!result.containsKey("error"), method + " failed: " + result);
            return result;
        }

        Object evaluate(String expression) throws Exception {
            Map<?, ?> response = cdp("Runtime.evaluate", Map.of("expression", expression,
                "returnByValue", true, "awaitPromise", true));
            require(!response.containsKey("exceptionDetails"), "JavaScript failed: " + response);
            Map<?, ?> result = object(response.get("result"));
            require(!"error".equals(result.get("subtype")), "JavaScript error: " + result);
            return result.get("value");
        }

        void close() throws Exception {
            if (!closeRequested) {
                assertSingleCreation();
                closeRequested = true;
                onEdt(() -> { browser.close(true); return null; });
            }
            await(closed, "browser onBeforeClose");
            onEdt(() -> { frame.dispose(); return null; });
        }

        void closeWithPendingVisibility(int pairs) throws Exception {
            assertSingleCreation();
            onEdt(() -> {
                Component surface = browser.getUIComponent();
                BufferedImage target = new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB);
                Graphics2D graphics = target.createGraphics();
                try {
                    // CefBrowserWr.paint runs doUpdate/UpdateUI and schedules its delayed Swing
                    // update. Offscreen graphics exercise that entry without capturing the desktop.
                    // No AppKit/CEF queue barrier before forced close: queued view work must retain
                    // the browser and resolve the native view only on its owning thread.
                    for (int index = 0; index < pairs; index++) {
                        surface.setSize(760 + index % 8, 540 + index % 8);
                        surface.paint(graphics);
                        browser.setWindowVisibility(false);
                        browser.setWindowVisibility(true);
                    }
                    closeRequested = true;
                    browser.close(true);
                    for (int index = 0; index < pairs; index++) {
                        surface.setSize(780 + index % 8, 560 + index % 8);
                        surface.paint(graphics);
                        browser.setWindowVisibility(true);
                        browser.setWindowVisibility(false);
                    }
                } finally {
                    graphics.dispose();
                }
                return null;
            });
            close();
        }
    }

    private void passed(String name, Map<String, ?> details) {
        Map<String, Object> check = new LinkedHashMap<>(details);
        check.put("name", name);
        check.put("passed", true);
        checks.add(check);
        System.out.println("PASS " + name);
    }

    private static Map<?, ?> object(Object value) {
        if (value instanceof Map<?, ?> map) return map;
        throw new AssertionError("Expected JSON object, got " + value);
    }

    private static double number(Map<?, ?> value, String key) { return ((Number) value.get(key)).doubleValue(); }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static <T> T await(CompletableFuture<T> future, String operation) throws Exception {
        try { return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS); }
        catch (Exception failure) { throw new IllegalStateException("Failed while waiting for " + operation, failure); }
    }

    private static <T> T onEdt(Callable<T> action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return action.call();
        FutureTask<T> task = new FutureTask<>(action);
        SwingUtilities.invokeLater(task);
        return task.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
}
