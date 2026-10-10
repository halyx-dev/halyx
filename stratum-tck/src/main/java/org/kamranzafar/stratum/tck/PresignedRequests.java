package org.kamranzafar.stratum.tck;

import org.kamranzafar.stratum.storage.PresignedUrl;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** Uses a {@link PresignedUrl} the way a client without cloud credentials would. */
public final class PresignedRequests {

    private PresignedRequests() {
    }

    /** Sends {@code body} to the URL with exactly the headers it requires. */
    public static HttpResponse<String> send(PresignedUrl url, HttpRequest.BodyPublisher body)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(url.uri()).method(url.method().name(), body);
        url.headers().forEach(request::header);
        try (HttpClient http = HttpClient.newHttpClient()) {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
