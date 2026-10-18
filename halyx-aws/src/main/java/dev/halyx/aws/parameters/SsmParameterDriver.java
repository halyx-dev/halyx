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
package dev.halyx.aws.parameters;

import dev.halyx.aws.AwsErrors;
import dev.halyx.aws.AwsProvider;
import dev.halyx.capability.Capabilities;
import dev.halyx.error.CloudException;
import dev.halyx.interceptor.AsyncInvocation;
import dev.halyx.parameters.Parameter;
import dev.halyx.parameters.ParameterCapability;
import dev.halyx.parameters.spi.AbstractParameterDriver;
import software.amazon.awssdk.services.ssm.SsmAsyncClient;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.ParameterNotFoundException;
import software.amazon.awssdk.services.ssm.model.ParameterType;
import software.amazon.awssdk.services.ssm.model.ParameterVersionNotFoundException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Parameters on SSM Parameter Store. Portable names map to hierarchical parameters with a leading
 * slash ({@code orders/db/url} becomes {@code /orders/db/url}). Values are written as {@code String}
 * parameters, or {@code SecureString} (KMS-encrypted) with the {@code aws.ssm.secure} store property.
 */
public final class SsmParameterDriver extends AbstractParameterDriver {

    public static final String SECURE = "aws.ssm.secure";

    private final SsmClient ssm;
    private final Supplier<SsmAsyncClient> ssmAsync;
    private final boolean secure;

    /** GetParameters accepts up to 10 names. */
    private static final int BATCH = 10;

    /** @param ssmAsync the non-blocking client for asynchronous calls, made when first asked for */
    public SsmParameterDriver(SsmClient ssm, boolean secure, boolean nativeNames, Supplier<SsmAsyncClient> ssmAsync) {
        super(AwsProvider.ID, Capabilities.of(ParameterCapability.VERSIONS), nativeNames);
        this.ssm = ssm;
        this.ssmAsync = ssmAsync;
        this.secure = secure;
    }

    /** Portable names gain a leading slash; native names are used as they are. */
    private String ssmName(String name) {
        return nativeNames ? name : "/" + name;
    }

    private String portable(String ssmName) {
        return nativeNames || !ssmName.startsWith("/") ? ssmName : ssmName.substring(1);
    }

    @Override
    public Optional<Parameter> get(String name) {
        return fetch(ssmName(name));
    }

    @Override
    public Optional<Parameter> get(String name, String version) {
        return fetch(ssmName(name) + ":" + version);
    }

    private Optional<Parameter> fetch(String selector) {
        try {
            var p = ssm.getParameter(b -> b.name(selector).withDecryption(true)).parameter();
            return Optional.of(toPortable(p));
        } catch (ParameterNotFoundException | ParameterVersionNotFoundException e) {
            return Optional.empty();
        }
    }

    @Override
    public Map<String, Parameter> list(String prefix) {
        if (nativeNames) {
            return listByNamePrefix(prefix);
        }
        Map<String, Parameter> found = new LinkedHashMap<>();
        if (!prefix.isEmpty()) {
            get(prefix).ifPresent(p -> found.put(p.name(), p)); // the prefix itself is not returned by path queries
        }
        ssm.getParametersByPathPaginator(b -> b.path("/" + prefix).recursive(true).withDecryption(true))
                .stream().flatMap(page -> page.parameters().stream()).forEach(p -> {
                    Parameter portable = toPortable(p);
                    if (matches(portable.name(), prefix)) {
                        found.put(portable.name(), portable);
                    }
                });
        return found;
    }

    /** Native names need not be paths: find them by name prefix, then fetch in batches. */
    private Map<String, Parameter> listByNamePrefix(String prefix) {
        List<String> names = new ArrayList<>();
        ssm.describeParametersPaginator(b -> {
            if (!prefix.isEmpty()) {
                b.parameterFilters(f -> f.key("Name").option("BeginsWith").values(prefix));
            }
        }).stream().flatMap(page -> page.parameters().stream()).forEach(meta -> {
            if (meta.name().startsWith(prefix)) {
                names.add(meta.name());
            }
        });
        Map<String, Parameter> found = new LinkedHashMap<>();
        for (int from = 0; from < names.size(); from += BATCH) {
            List<String> batch = names.subList(from, Math.min(from + BATCH, names.size()));
            ssm.getParameters(b -> b.names(batch).withDecryption(true)).parameters()
                    .forEach(p -> found.put(p.name(), toPortable(p)));
        }
        return found;
    }

    @Override
    public Parameter put(String name, String value) {
        var response = ssm.putParameter(b -> b.name(ssmName(name)).value(value).overwrite(true)
                .type(secure ? ParameterType.SECURE_STRING : ParameterType.STRING));
        return new Parameter(name, value, String.valueOf(response.version()), null);
    }

    @Override
    public void delete(String name) {
        try {
            ssm.deleteParameter(b -> b.name(ssmName(name)));
        } catch (ParameterNotFoundException ignored) {
            // already gone
        }
    }

    @Override
    public CompletableFuture<Optional<Parameter>> getAsync(String name, Executor blocking) {
        return fetchAsync(ssmName(name));
    }

    @Override
    public CompletableFuture<Optional<Parameter>> getAsync(String name, String version, Executor blocking) {
        return fetchAsync(ssmName(name) + ":" + version);
    }

    private CompletableFuture<Optional<Parameter>> fetchAsync(String selector) {
        return ssmAsync.get().getParameter(b -> b.name(selector).withDecryption(true))
                .thenApply(response -> Optional.of(toPortable(response.parameter())))
                .exceptionallyCompose(error -> notFound(error) ? CompletableFuture.completedFuture(Optional.empty())
                        : CompletableFuture.failedFuture(error));
    }

    private static boolean notFound(Throwable error) {
        Throwable cause = AsyncInvocation.unwrap(error);
        return cause instanceof ParameterNotFoundException || cause instanceof ParameterVersionNotFoundException;
    }

    @Override
    public CompletableFuture<Map<String, Parameter>> listAsync(String prefix, Executor blocking) {
        if (nativeNames) {
            return listByNamePrefixAsync(prefix);
        }
        Map<String, Parameter> found = new LinkedHashMap<>();
        CompletableFuture<Void> itself = prefix.isEmpty() ? CompletableFuture.completedFuture(null)
                : getAsync(prefix, blocking).thenAccept(p -> p.ifPresent(parameter -> found.put(parameter.name(), parameter)));
        // the pages arrive one after another, after the prefix itself
        return itself.thenCompose(done -> ssmAsync.get()
                .getParametersByPathPaginator(b -> b.path("/" + prefix).recursive(true).withDecryption(true))
                .subscribe(page -> page.parameters().forEach(p -> {
                    Parameter portable = toPortable(p);
                    if (matches(portable.name(), prefix)) {
                        found.put(portable.name(), portable);
                    }
                }))).thenApply(done -> found);
    }

    private CompletableFuture<Map<String, Parameter>> listByNamePrefixAsync(String prefix) {
        List<String> names = new ArrayList<>();
        return ssmAsync.get().describeParametersPaginator(b -> {
            if (!prefix.isEmpty()) {
                b.parameterFilters(f -> f.key("Name").option("BeginsWith").values(prefix));
            }
        }).subscribe(page -> page.parameters().forEach(meta -> {
            if (meta.name().startsWith(prefix)) {
                names.add(meta.name());
            }
        })).thenCompose(done -> {
            List<CompletableFuture<List<software.amazon.awssdk.services.ssm.model.Parameter>>> batches = new ArrayList<>();
            for (int from = 0; from < names.size(); from += BATCH) {
                List<String> batch = names.subList(from, Math.min(from + BATCH, names.size()));
                batches.add(ssmAsync.get().getParameters(b -> b.names(batch).withDecryption(true))
                        .thenApply(response -> response.parameters()));
            }
            return CompletableFuture.allOf(batches.toArray(CompletableFuture[]::new)).thenApply(all -> {
                Map<String, Parameter> found = new LinkedHashMap<>();
                batches.forEach(batch -> batch.join().forEach(p -> found.put(p.name(), toPortable(p))));
                return found;
            });
        });
    }

    @Override
    public CompletableFuture<Parameter> putAsync(String name, String value, Executor blocking) {
        return ssmAsync.get().putParameter(b -> b.name(ssmName(name)).value(value).overwrite(true)
                        .type(secure ? ParameterType.SECURE_STRING : ParameterType.STRING))
                .thenApply(response -> new Parameter(name, value, String.valueOf(response.version()), null));
    }

    @Override
    public CompletableFuture<Void> deleteAsync(String name, Executor blocking) {
        return ssmAsync.get().deleteParameter(b -> b.name(ssmName(name))).<Void>thenApply(response -> null)
                .exceptionallyCompose(error -> AsyncInvocation.unwrap(error) instanceof ParameterNotFoundException
                        ? CompletableFuture.completedFuture(null) : CompletableFuture.failedFuture(error));
    }

    private Parameter toPortable(software.amazon.awssdk.services.ssm.model.Parameter p) {
        return new Parameter(portable(p.name()), p.value(), String.valueOf(p.version()), p.lastModifiedDate());
    }

    @Override
    protected Optional<CloudException> translateNative(Throwable error) {
        return AwsErrors.translate(error);
    }

    @Override
    protected List<Object> nativeClients() {
        return List.of(ssm);
    }
}
