/*
 * Copyright 2026 Kamran Zafar
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.halyx.samples.orders.e2e;

import dev.halyx.json.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The orders service's HTTP API as the end-to-end tests use it: plain HTTP and Halyx' JSON reader, so the same tests
 * run in process, against a container, and against a deployment on a real cloud, on Boot 3 and 4 alike.
 */
public final class OrdersClient {

    /** A response: its status and body, the body parsed when it is JSON. */
    public record Response(int status, String body, Map<String, List<String>> headers) {

        @SuppressWarnings("unchecked")
        public Map<String, Object> json() {
            return (Map<String, Object>) Json.parse(body);
        }

        @SuppressWarnings("unchecked")
        public List<Map<String, Object>> jsonList() {
            return (List<Map<String, Object>>) Json.parse(body);
        }
    }

    private final URI base;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public OrdersClient(URI base) {
        this.base = base;
    }

    public URI base() {
        return base;
    }

    public Response get(String path) {
        return send(HttpRequest.newBuilder(base.resolve(path)).GET());
    }

    public Response post(String path, Object json, String idempotencyKey) {
        HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(json)));
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return send(request);
    }

    private Response send(HttpRequest.Builder request) {
        try {
            HttpResponse<String> response = http.send(request.timeout(Duration.ofSeconds(30)).build(),
                    HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body(), response.headers().map());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
