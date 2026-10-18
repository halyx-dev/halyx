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
package dev.halyx.gcp.parameters;

import com.google.api.gax.rpc.ApiException;
import com.google.api.gax.rpc.StatusCode;
import com.google.cloud.parametermanager.v1.CreateParameterRequest;
import com.google.cloud.parametermanager.v1.CreateParameterVersionRequest;
import com.google.cloud.parametermanager.v1.DeleteParameterRequest;
import com.google.cloud.parametermanager.v1.DeleteParameterVersionRequest;
import com.google.cloud.parametermanager.v1.GetParameterVersionRequest;
import com.google.cloud.parametermanager.v1.ListParameterVersionsRequest;
import com.google.cloud.parametermanager.v1.ListParametersRequest;
import com.google.cloud.parametermanager.v1.LocationName;
import com.google.cloud.parametermanager.v1.ParameterFormat;
import com.google.cloud.parametermanager.v1.ParameterManagerClient;
import com.google.cloud.parametermanager.v1.ParameterName;
import com.google.cloud.parametermanager.v1.ParameterVersion;
import com.google.cloud.parametermanager.v1.ParameterVersionName;
import com.google.cloud.parametermanager.v1.ParameterVersionPayload;
import com.google.cloud.parametermanager.v1.RenderParameterVersionRequest;
import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import dev.halyx.capability.Capabilities;
import dev.halyx.error.CloudException;
import dev.halyx.gcp.GcpErrors;
import dev.halyx.gcp.GcpFutures;
import dev.halyx.gcp.GcpProvider;
import dev.halyx.parameters.Parameter;
import dev.halyx.parameters.ParameterCapability;
import dev.halyx.parameters.spi.AbstractParameterDriver;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Parameters on Google Parameter Manager, as {@code UNFORMATTED} parameters. Ids follow the Secret
 * Manager rules, so {@code /} is stored as {@code __}.
 *
 * <p>Parameter Manager has no "latest" alias and the caller names each version. Writes therefore use
 * ids that sort by creation time ({@code v<epoch-millis>-<random>}), and reads take the newest enabled
 * version by creation time (also correct for versions other tools created). Reading costs a version
 * listing plus a fetch, so give the store a {@code cacheTtl}.
 *
 * <p>With the {@code gcp.parametermanager.render} store property, values are rendered: references to
 * Secret Manager secrets ({@code __REF__(//secretmanager.googleapis.com/...)}) are resolved.
 */
public final class ParameterManagerDriver extends AbstractParameterDriver {

    public static final String RENDER = "gcp.parametermanager.render";

    private final ParameterManagerClient client;
    private final String project;
    private final String location;
    private final boolean render;

    public ParameterManagerDriver(ParameterManagerClient client, String project, String location, boolean render,
                                  boolean nativeNames) {
        super(GcpProvider.ID, Capabilities.of(ParameterCapability.VERSIONS), nativeNames);
        this.client = client;
        this.project = project;
        this.location = location;
        this.render = render;
    }

    String encode(String name) {
        return nativeNames ? name : name.replace("/", "__");
    }

    String decode(String id) {
        return nativeNames ? id : id.replace("__", "/");
    }

    /** Version ids that sort in creation order: {@code v} + 13-digit epoch millis + random suffix. */
    static String newVersionId(Instant now) {
        return String.format("v%013d-%04x", now.toEpochMilli(), ThreadLocalRandom.current().nextInt(0x10000));
    }

    /** The newest enabled version: latest creation time, then highest id. */
    static Optional<ParameterVersion> latest(Iterable<ParameterVersion> versions) {
        Comparator<ParameterVersion> byAge = Comparator
                .comparing((ParameterVersion v) -> v.getCreateTime().getSeconds())
                .thenComparing(v -> v.getCreateTime().getNanos())
                .thenComparing(ParameterVersion::getName);
        ParameterVersion newest = null;
        for (ParameterVersion version : versions) {
            if (!version.getDisabled() && (newest == null || byAge.compare(version, newest) > 0)) {
                newest = version;
            }
        }
        return Optional.ofNullable(newest);
    }

    private ParameterName parameter(String name) {
        return ParameterName.of(project, location, encode(name));
    }

    @Override
    public Optional<Parameter> get(String name) {
        try {
            return latest(client.listParameterVersions(parameter(name)).iterateAll())
                    .flatMap(version -> read(name, ParameterVersionName.parse(version.getName())));
        } catch (ApiException e) {
            if (e.getStatusCode().getCode() == StatusCode.Code.NOT_FOUND) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public Optional<Parameter> get(String name, String version) {
        return read(name, ParameterVersionName.of(project, location, encode(name), version));
    }

    private Optional<Parameter> read(String name, ParameterVersionName versionName) {
        try {
            ParameterVersion version = client.getParameterVersion(versionName);
            if (version.getDisabled()) {
                return Optional.empty();
            }
            ByteString data = render
                    ? client.renderParameterVersion(versionName).getRenderedPayload()
                    : version.getPayload().getData();
            return Optional.of(new Parameter(name, data.toStringUtf8(), versionName.getParameterVersion(),
                    instant(version.getCreateTime())));
        } catch (ApiException e) {
            if (e.getStatusCode().getCode() == StatusCode.Code.NOT_FOUND) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public Map<String, Parameter> list(String prefix) {
        List<String> names = new ArrayList<>();
        client.listParameters(LocationName.of(project, location)).iterateAll().forEach(p -> {
            String name = decode(ParameterName.parse(p.getName()).getParameter());
            if (matches(name, prefix)) {
                names.add(name);
            }
        });
        Map<String, Parameter> found = new LinkedHashMap<>();
        for (String name : names) {
            get(name).ifPresent(p -> found.put(name, p));
        }
        return found;
    }

    @Override
    public Parameter put(String name, String value) {
        ParameterVersion version = newVersion(value);
        String versionId = newVersionId(Instant.now());
        ParameterVersion created;
        try {
            created = client.createParameterVersion(parameter(name), version, versionId);
        } catch (ApiException e) {
            if (e.getStatusCode().getCode() != StatusCode.Code.NOT_FOUND) {
                throw e;
            }
            client.createParameter(LocationName.of(project, location), newParameter(), encode(name));
            created = client.createParameterVersion(parameter(name), version, versionId);
        }
        return stored(name, value, created);
    }

    private static ParameterVersion newVersion(String value) {
        return ParameterVersion.newBuilder()
                .setPayload(ParameterVersionPayload.newBuilder().setData(ByteString.copyFrom(value, StandardCharsets.UTF_8)))
                .build();
    }

    private static com.google.cloud.parametermanager.v1.Parameter newParameter() {
        return com.google.cloud.parametermanager.v1.Parameter.newBuilder().setFormat(ParameterFormat.UNFORMATTED).build();
    }

    private static Parameter stored(String name, String value, ParameterVersion created) {
        return new Parameter(name, value, ParameterVersionName.parse(created.getName()).getParameterVersion(),
                instant(created.getCreateTime()));
    }

    @Override
    public void delete(String name) {
        try {
            // A parameter can only be deleted once it has no versions.
            for (ParameterVersion version : client.listParameterVersions(parameter(name)).iterateAll()) {
                client.deleteParameterVersion(version.getName());
            }
            client.deleteParameter(parameter(name));
        } catch (ApiException e) {
            if (e.getStatusCode().getCode() != StatusCode.Code.NOT_FOUND) {
                throw e;
            }
        }
    }

    @Override
    public CompletableFuture<Optional<Parameter>> getAsync(String name, Executor blocking) {
        return versionsAsync(name)
                .thenCompose(versions -> latest(versions).map(version -> readAsync(name, ParameterVersionName.parse(version.getName())))
                        .orElseGet(() -> CompletableFuture.completedFuture(Optional.empty())))
                .exceptionallyCompose(ParameterManagerDriver::emptyIfNotFound);
    }

    @Override
    public CompletableFuture<Optional<Parameter>> getAsync(String name, String version, Executor blocking) {
        return readAsync(name, ParameterVersionName.of(project, location, encode(name), version));
    }

    private CompletableFuture<List<ParameterVersion>> versionsAsync(String name) {
        ListParameterVersionsRequest request = ListParameterVersionsRequest.newBuilder()
                .setParent(parameter(name).toString()).build();
        return GcpFutures.of(client.listParameterVersionsPagedCallable().futureCall(request))
                .thenCompose(response -> GcpFutures.allPages(response.getPage()));
    }

    private CompletableFuture<Optional<Parameter>> readAsync(String name, ParameterVersionName versionName) {
        GetParameterVersionRequest request = GetParameterVersionRequest.newBuilder().setName(versionName.toString()).build();
        return GcpFutures.of(client.getParameterVersionCallable().futureCall(request))
                .thenCompose(version -> {
                    if (version.getDisabled()) {
                        return CompletableFuture.completedFuture(Optional.<Parameter>empty());
                    }
                    CompletableFuture<ByteString> data = render
                            ? GcpFutures.of(client.renderParameterVersionCallable().futureCall(RenderParameterVersionRequest
                                    .newBuilder().setName(versionName.toString()).build())).thenApply(r -> r.getRenderedPayload())
                            : CompletableFuture.completedFuture(version.getPayload().getData());
                    return data.thenApply(bytes -> Optional.of(new Parameter(name, bytes.toStringUtf8(),
                            versionName.getParameterVersion(), instant(version.getCreateTime()))));
                })
                .exceptionallyCompose(ParameterManagerDriver::emptyIfNotFound);
    }

    private static CompletableFuture<Optional<Parameter>> emptyIfNotFound(Throwable error) {
        return GcpFutures.hasCode(error, StatusCode.Code.NOT_FOUND)
                ? CompletableFuture.completedFuture(Optional.empty()) : CompletableFuture.failedFuture(error);
    }

    @Override
    public CompletableFuture<Map<String, Parameter>> listAsync(String prefix, Executor blocking) {
        ListParametersRequest request = ListParametersRequest.newBuilder()
                .setParent(LocationName.of(project, location).toString()).build();
        return GcpFutures.of(client.listParametersPagedCallable().futureCall(request))
                .thenCompose(response -> GcpFutures.allPages(response.getPage()))
                .thenCompose(parameters -> {
                    List<CompletableFuture<Optional<Parameter>>> reads = parameters.stream()
                            .map(p -> decode(ParameterName.parse(p.getName()).getParameter()))
                            .filter(name -> matches(name, prefix))
                            .map(name -> getAsync(name, blocking)).toList();
                    return CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new)).thenApply(all -> {
                        Map<String, Parameter> found = new LinkedHashMap<>();
                        reads.forEach(read -> read.join().ifPresent(p -> found.put(p.name(), p)));
                        return found;
                    });
                });
    }

    @Override
    public CompletableFuture<Parameter> putAsync(String name, String value, Executor blocking) {
        CreateParameterVersionRequest create = CreateParameterVersionRequest.newBuilder()
                .setParent(parameter(name).toString()).setParameterVersionId(newVersionId(Instant.now()))
                .setParameterVersion(newVersion(value)).build();
        return GcpFutures.of(client.createParameterVersionCallable().futureCall(create))
                .exceptionallyCompose(error -> {
                    if (!GcpFutures.hasCode(error, StatusCode.Code.NOT_FOUND)) {
                        return CompletableFuture.failedFuture(error);
                    }
                    CreateParameterRequest parameter = CreateParameterRequest.newBuilder()
                            .setParent(LocationName.of(project, location).toString()).setParameterId(encode(name))
                            .setParameter(newParameter()).build();
                    return GcpFutures.of(client.createParameterCallable().futureCall(parameter))
                            .thenCompose(created -> GcpFutures.of(client.createParameterVersionCallable().futureCall(create)));
                })
                .thenApply(created -> stored(name, value, created));
    }

    /** A parameter can only be deleted once it has no versions. */
    @Override
    public CompletableFuture<Void> deleteAsync(String name, Executor blocking) {
        return versionsAsync(name).thenCompose(versions -> CompletableFuture.allOf(versions.stream()
                        .map(version -> GcpFutures.of(client.deleteParameterVersionCallable().futureCall(
                                DeleteParameterVersionRequest.newBuilder().setName(version.getName()).build())))
                        .toArray(CompletableFuture[]::new)))
                .thenCompose(done -> GcpFutures.of(client.deleteParameterCallable().futureCall(
                        DeleteParameterRequest.newBuilder().setName(parameter(name).toString()).build())))
                .<Void>thenApply(empty -> null)
                .exceptionallyCompose(error -> GcpFutures.hasCode(error, StatusCode.Code.NOT_FOUND)
                        ? CompletableFuture.completedFuture(null) : CompletableFuture.failedFuture(error));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp.getSeconds() == 0 && timestamp.getNanos() == 0
                ? null : Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }

    @Override
    protected Optional<CloudException> translateNative(Throwable error) {
        return GcpErrors.translate(error);
    }

    @Override
    protected List<Object> nativeClients() {
        return List.of(client);
    }
}
