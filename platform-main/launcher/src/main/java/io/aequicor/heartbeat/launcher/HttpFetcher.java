package io.aequicor.heartbeat.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;

/**
 * Скачивает jar бандла по его адресу {@link TrainManifest.Bundle#url()}, а без него — по {@code baseUrl + jar} (рядом
 * с манифестом поезда). Допускается HTTPS и loopback по HTTP. Без {@code baseUrl} ({@code null}) качаются только бандлы
 * со своим адресом. Целостность проверяет {@link TrainStore} по SHA-256 манифеста, поэтому канал сам по себе не
 * доверенный.
 */
public final class HttpFetcher implements TrainStore.Fetcher {
    static final int TIMEOUT_MILLIS = 30_000;

    private final String baseUrl;

    public HttpFetcher(String baseUrl) {
        if (baseUrl != null && !TrainManifest.isAllowedUrl(baseUrl)) {
            throw new IllegalArgumentException("Bundle download needs https: " + baseUrl);
        }
        this.baseUrl = baseUrl == null || baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
    }

    @Override
    public InputStream open(TrainManifest.Bundle bundle) throws IOException {
        URL url;
        if (bundle.url() != null) {
            url = URI.create(bundle.url()).toURL();
        } else if (baseUrl != null) {
            url = URI.create(baseUrl).resolve(bundle.jar()).toURL();
        } else {
            throw new IOException("Bundle " + bundle.jar() + " has to be downloaded, but the seed has no baseUrl");
        }
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
