package io.aequicor.heartbeat.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;

/**
 * Скачивает jar бандла по адресу {@code baseUrl + jar} (рядом с манифестом поезда). Допускается HTTPS и loopback по HTTP.
 * Целостность проверяет {@link TrainStore} по SHA-256 манифеста, поэтому канал сам по себе не доверенный.
 */
public final class HttpFetcher implements TrainStore.Fetcher {
    static final int TIMEOUT_MILLIS = 30_000;

    private final String baseUrl;

    public HttpFetcher(String baseUrl) {
        URI uri = URI.create(baseUrl);
        boolean loopback = "http".equals(uri.getScheme())
            && ("localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost()));
        if (!"https".equals(uri.getScheme()) && !loopback) {
            throw new IllegalArgumentException("Bundle download needs https: " + baseUrl);
        }
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
    }

    @Override
    public InputStream open(TrainManifest.Bundle bundle) throws IOException {
        URL url = URI.create(baseUrl).resolve(bundle.jar()).toURL();
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(TIMEOUT_MILLIS);
        connection.setReadTimeout(TIMEOUT_MILLIS);
        int code = connection.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            connection.disconnect();
            throw new IOException("Download of " + bundle.jar() + " failed: HTTP " + code);
        }
        return connection.getInputStream();
    }
}
