import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.cef.browser.CefRequestContext;
import org.cef.callback.CefAuthCallback;
import org.cef.callback.CefNativeAdapter;
import org.cef.callback.CefURLRequestClient;
import org.cef.network.CefRequest;
import org.cef.network.CefResponse;
import org.cef.network.CefURLRequest;

/** Native URLRequest/context ABI smoke; callbacks never reenter native request methods. */
public final class ProbeDownload {
    private static final int TIMEOUT_SECONDS = 60;

    public static void verify(CefRequestContext a, CefRequestContext b, String origin,
            String token, String marker) throws Exception {
        verifyCookies(a, origin, marker);
        verifyCookies(b, origin, null);
        try (Request request = new Request(a, origin, "/held-response")) {
            request.client.firstData.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            request.nativeRequest.cancel();
            request.awaitComplete();
            require(request.nativeRequest.getRequestStatus() == CefURLRequest.Status.UR_CANCELED,
                "Native URLRequest cancellation was not acknowledged");
            require(request.client.body.size() < 1024 * 1024, "Held response completed before cancellation");
        } finally {
            HttpRequest release = HttpRequest.newBuilder(URI.create(origin + "/release-held"))
                .header("X-Probe-Token", token).timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .POST(HttpRequest.BodyPublishers.noBody()).build();
            try (HttpClient control = HttpClient.newHttpClient()) {
                require(control.send(release, HttpResponse.BodyHandlers.discarding()).statusCode() == 200,
                    "Unable to release held fixture response");
            }
        }
    }

    private static void verifyCookies(CefRequestContext context, String origin, String expected) throws Exception {
        try (Request request = new Request(context, origin, "/cookies")) {
            request.awaitComplete();
            require(request.nativeRequest.getRequestStatus() == CefURLRequest.Status.UR_SUCCESS,
                "Context URLRequest failed: " + request.nativeRequest.getRequestError());
            CefResponse response = Objects.requireNonNull(request.nativeRequest.getResponse());
            try {
                require(response.getStatus() == 200, "Context URLRequest returned HTTP " + response.getStatus());
            } finally {
                response.dispose();
            }
            Map<?, ?> cookies = (Map<?, ?>) ProbeJson.parse(request.client.body.toString(StandardCharsets.UTF_8));
            require(Objects.equals(expected, cookies.get("hb_session")), "Context URLRequest session cookie mismatch");
            require(Objects.equals(expected, cookies.get("hb_persistent")), "Context URLRequest persistent cookie mismatch");
        }
    }

    private static final class Request implements AutoCloseable {
        final Client client = new Client();
        final CefRequestContext context;
        final CefRequest request;
        final CefURLRequest nativeRequest;

        Request(CefRequestContext anchor, String origin, String path) {
            context = Objects.requireNonNull(CefRequestContext.createContext(anchor, null));
            request = Objects.requireNonNull(CefRequest.create());
            request.setURL(origin + path);
            request.setMethod("GET");
            request.setFirstPartyForCookies(origin);
            request.setFlags(CefRequest.CefUrlRequestFlags.UR_FLAG_ALLOW_CACHED_CREDENTIALS
                | CefRequest.CefUrlRequestFlags.UR_FLAG_SKIP_CACHE);
            nativeRequest = Objects.requireNonNull(CefURLRequest.create(request, client, context),
                "Context-aware native URLRequest creation failed");
        }

        void awaitComplete() throws Exception { client.complete.get(TIMEOUT_SECONDS, TimeUnit.SECONDS); }

        @Override public void close() throws Exception {
            if (!client.complete.isDone()) {
                nativeRequest.cancel();
                awaitComplete();
            }
            nativeRequest.dispose();
            request.dispose();
            CompletableFuture<Void> released = new CompletableFuture<>();
            context.dispose(() -> released.complete(null));
            released.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
    }

    private static final class Client extends CefNativeAdapter implements CefURLRequestClient {
        final CompletableFuture<Void> complete = new CompletableFuture<>();
        final CompletableFuture<Void> firstData = new CompletableFuture<>();
        final ByteArrayOutputStream body = new ByteArrayOutputStream();

        @Override public void onRequestComplete(CefURLRequest request) { complete.complete(null); }
        @Override public void onUploadProgress(CefURLRequest request, int current, int total) {}
        @Override public void onDownloadProgress(CefURLRequest request, int current, int total) {}
        @Override public void onDownloadData(CefURLRequest request, byte[] data, int length) {
            body.write(data, 0, length);
            firstData.complete(null);
        }
        @Override public boolean getAuthCredentials(boolean proxy, String host, int port,
                String realm, String scheme, CefAuthCallback callback) { return false; }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
