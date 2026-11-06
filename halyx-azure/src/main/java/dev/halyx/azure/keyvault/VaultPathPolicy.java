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
package dev.halyx.azure.keyvault;

import com.azure.core.http.HttpPipelineCallContext;
import com.azure.core.http.HttpPipelineNextPolicy;
import com.azure.core.http.HttpResponse;
import com.azure.core.http.policy.HttpPipelinePolicy;
import reactor.core.publisher.Mono;

import java.net.MalformedURLException;
import java.net.URL;

/**
 * Puts a vault URL's path back in front of each request's path. The cryptography client keeps only the scheme, host
 * and port of a key identifier, and reads the path as the key's collection, name and version. Key Vault URLs have no
 * path, but emulators serve vaults under one ({@code /devstoreaccount1-keyvault}), so the client is given the
 * identifier without the path and this policy restores it on the wire.
 */
final class VaultPathPolicy implements HttpPipelinePolicy {

    private final String path;

    /** @param path the vault URL's path, such as {@code /devstoreaccount1-keyvault}, without a trailing slash */
    VaultPathPolicy(String path) {
        this.path = path;
    }

    @Override
    public Mono<HttpResponse> process(HttpPipelineCallContext context, HttpPipelineNextPolicy next) {
        URL url = context.getHttpRequest().getUrl();
        if (!url.getPath().startsWith(path + "/")) {
            try {
                String query = url.getQuery() == null ? "" : "?" + url.getQuery();
                context.getHttpRequest().setUrl(new URL(url.getProtocol(), url.getHost(), url.getPort(),
                        path + url.getPath() + query));
            } catch (MalformedURLException e) {
                return Mono.error(e);
            }
        }
        return next.process();
    }
}
