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
package org.kamranzafar.cirrus.tck;

import org.kamranzafar.cirrus.storage.PresignedUrl;

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
