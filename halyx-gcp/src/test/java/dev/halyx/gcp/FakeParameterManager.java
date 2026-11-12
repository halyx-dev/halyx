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
package dev.halyx.gcp;

import com.google.cloud.kms.v1.CryptoKey;
import com.google.cloud.kms.v1.CryptoKeyVersion;
import com.google.cloud.kms.v1.GetCryptoKeyRequest;
import com.google.cloud.kms.v1.KeyManagementServiceGrpc;
import com.google.cloud.kms.v1.ListCryptoKeyVersionsRequest;
import com.google.cloud.kms.v1.ListCryptoKeyVersionsResponse;
import com.google.cloud.parametermanager.v1.CreateParameterRequest;
import com.google.cloud.parametermanager.v1.CreateParameterVersionRequest;
import com.google.cloud.parametermanager.v1.DeleteParameterRequest;
import com.google.cloud.parametermanager.v1.DeleteParameterVersionRequest;
import com.google.cloud.parametermanager.v1.GetParameterVersionRequest;
import com.google.cloud.parametermanager.v1.ListParameterVersionsRequest;
import com.google.cloud.parametermanager.v1.ListParameterVersionsResponse;
import com.google.cloud.parametermanager.v1.ListParametersRequest;
import com.google.cloud.parametermanager.v1.ListParametersResponse;
import com.google.cloud.parametermanager.v1.Parameter;
import com.google.cloud.parametermanager.v1.ParameterManagerGrpc;
import com.google.cloud.parametermanager.v1.ParameterVersion;
import com.google.cloud.parametermanager.v1.ParameterVersionPayload;
import com.google.cloud.parametermanager.v1.RenderParameterVersionRequest;
import com.google.cloud.parametermanager.v1.RenderParameterVersionResponse;
import com.google.protobuf.ByteString;
import com.google.protobuf.Empty;
import com.google.protobuf.Timestamp;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An in-memory Parameter Manager speaking the real gRPC protocol, following the documented behaviour:
 * caller-chosen version ids, version listings without payloads, deletes refused while versions exist,
 * and rendering of {@code __REF__(...)} secret references. It checks the driver's use of the API, not
 * GCP's exact semantics; run the same TCK against real GCP to confirm those. It also serves the metadata of any Cloud
 * KMS key as a symmetric key, for customer-managed keys.
 */
final class FakeParameterManager extends ParameterManagerGrpc.ParameterManagerImplBase implements AutoCloseable {

    static final String RENDERED_SECRET = "rendered-secret-value";

    private final Map<String, Parameter> parameters = new ConcurrentSkipListMap<>();
    private final Map<String, Map<String, ParameterVersion>> versions = new ConcurrentHashMap<>();
    private final AtomicLong clock = new AtomicLong(Instant.parse("2026-10-10T12:00:00Z").toEpochMilli());
    private final Server server;

    FakeParameterManager() throws IOException {
        server = ServerBuilder.forPort(0).addService(this).addService(new FakeKms()).build().start();
    }

    int port() {
        return server.getPort();
    }

    /** The customer-managed key the parameter was created with ({@code kms_key}); empty for Google's default. */
    Optional<String> kmsKey(String parameterName) {
        return Optional.ofNullable(parameters.get(parameterName)).filter(Parameter::hasKmsKey).map(Parameter::getKmsKey);
    }

    /** Every crypto key is an enabled symmetric key with one version. */
    private static final class FakeKms extends KeyManagementServiceGrpc.KeyManagementServiceImplBase {

        private static CryptoKeyVersion version(String key) {
            return CryptoKeyVersion.newBuilder().setName(key + "/cryptoKeyVersions/1")
                    .setState(CryptoKeyVersion.CryptoKeyVersionState.ENABLED)
                    .setAlgorithm(CryptoKeyVersion.CryptoKeyVersionAlgorithm.GOOGLE_SYMMETRIC_ENCRYPTION).build();
        }

        @Override
        public void getCryptoKey(GetCryptoKeyRequest request, StreamObserver<CryptoKey> response) {
            response.onNext(CryptoKey.newBuilder().setName(request.getName())
                    .setPurpose(CryptoKey.CryptoKeyPurpose.ENCRYPT_DECRYPT).setPrimary(version(request.getName())).build());
            response.onCompleted();
        }

        @Override
        public void listCryptoKeyVersions(ListCryptoKeyVersionsRequest request,
                                          StreamObserver<ListCryptoKeyVersionsResponse> response) {
            response.onNext(ListCryptoKeyVersionsResponse.newBuilder().addCryptoKeyVersions(version(request.getParent()))
                    .setTotalSize(1).build());
            response.onCompleted();
        }
    }

    /** Adds a version with a caller-chosen id, as another tool would. */
    void addForeignVersion(String parameterName, String versionId, String value) {
        versions.get(parameterName).put(parameterName + "/versions/" + versionId, version(parameterName + "/versions/" + versionId,
                ByteString.copyFromUtf8(value)));
    }

    private ParameterVersion version(String name, ByteString data) {
        long millis = clock.addAndGet(1000); // strictly increasing creation times
        return ParameterVersion.newBuilder().setName(name)
                .setCreateTime(Timestamp.newBuilder().setSeconds(millis / 1000).setNanos((int) (millis % 1000) * 1_000_000))
                .setPayload(ParameterVersionPayload.newBuilder().setData(data))
                .build();
    }

    @Override
    public void createParameter(CreateParameterRequest request, StreamObserver<Parameter> response) {
        String name = request.getParent() + "/parameters/" + request.getParameterId();
        if (parameters.containsKey(name)) {
            response.onError(Status.ALREADY_EXISTS.withDescription(name).asRuntimeException());
            return;
        }
        Parameter parameter = request.getParameter().toBuilder().setName(name).build();
        parameters.put(name, parameter);
        versions.put(name, new ConcurrentSkipListMap<>());
        reply(response, parameter);
    }

    @Override
    public void listParameters(ListParametersRequest request, StreamObserver<ListParametersResponse> response) {
        var page = ListParametersResponse.newBuilder();
        parameters.forEach((name, p) -> {
            if (name.startsWith(request.getParent() + "/parameters/")) {
                page.addParameters(p);
            }
        });
        reply(response, page.build());
    }

    @Override
    public void deleteParameter(DeleteParameterRequest request, StreamObserver<Empty> response) {
        if (!parameters.containsKey(request.getName())) {
            response.onError(Status.NOT_FOUND.withDescription(request.getName()).asRuntimeException());
        } else if (!versions.get(request.getName()).isEmpty()) {
            response.onError(Status.FAILED_PRECONDITION.withDescription("Parameter has versions").asRuntimeException());
        } else {
            parameters.remove(request.getName());
            versions.remove(request.getName());
            reply(response, Empty.getDefaultInstance());
        }
    }

    @Override
    public void createParameterVersion(CreateParameterVersionRequest request, StreamObserver<ParameterVersion> response) {
        Map<String, ParameterVersion> existing = versions.get(request.getParent());
        if (existing == null) {
            response.onError(Status.NOT_FOUND.withDescription(request.getParent()).asRuntimeException());
            return;
        }
        String name = request.getParent() + "/versions/" + request.getParameterVersionId();
        if (existing.containsKey(name)) {
            response.onError(Status.ALREADY_EXISTS.withDescription(name).asRuntimeException());
            return;
        }
        ParameterVersion version = version(name, request.getParameterVersion().getPayload().getData());
        existing.put(name, version);
        reply(response, version.toBuilder().clearPayload().build());
    }

    @Override
    public void listParameterVersions(ListParameterVersionsRequest request, StreamObserver<ListParameterVersionsResponse> response) {
        Map<String, ParameterVersion> existing = versions.get(request.getParent());
        if (existing == null) {
            response.onError(Status.NOT_FOUND.withDescription(request.getParent()).asRuntimeException());
            return;
        }
        var page = ListParameterVersionsResponse.newBuilder();
        existing.values().forEach(v -> page.addParameterVersions(v.toBuilder().clearPayload())); // BASIC view
        reply(response, page.build());
    }

    @Override
    public void getParameterVersion(GetParameterVersionRequest request, StreamObserver<ParameterVersion> response) {
        ParameterVersion version = find(request.getName());
        if (version == null) {
            response.onError(Status.NOT_FOUND.withDescription(request.getName()).asRuntimeException());
        } else {
            reply(response, version);
        }
    }

    @Override
    public void renderParameterVersion(RenderParameterVersionRequest request, StreamObserver<RenderParameterVersionResponse> response) {
        ParameterVersion version = find(request.getName());
        if (version == null) {
            response.onError(Status.NOT_FOUND.withDescription(request.getName()).asRuntimeException());
            return;
        }
        String raw = version.getPayload().getData().toStringUtf8();
        String rendered = raw.replaceAll("__REF__\\([^)]*\\)", RENDERED_SECRET);
        reply(response, RenderParameterVersionResponse.newBuilder().setParameterVersion(version.getName())
                .setPayload(version.getPayload()).setRenderedPayload(ByteString.copyFromUtf8(rendered)).build());
    }

    @Override
    public void deleteParameterVersion(DeleteParameterVersionRequest request, StreamObserver<Empty> response) {
        String parameter = request.getName().substring(0, request.getName().indexOf("/versions/"));
        Map<String, ParameterVersion> existing = versions.get(parameter);
        if (existing == null || existing.remove(request.getName()) == null) {
            response.onError(Status.NOT_FOUND.withDescription(request.getName()).asRuntimeException());
        } else {
            reply(response, Empty.getDefaultInstance());
        }
    }

    private ParameterVersion find(String versionName) {
        int at = versionName.indexOf("/versions/");
        Map<String, ParameterVersion> existing = at < 0 ? null : versions.get(versionName.substring(0, at));
        return existing == null ? null : existing.get(versionName);
    }

    private static <T> void reply(StreamObserver<T> response, T value) {
        response.onNext(value);
        response.onCompleted();
    }

    @Override
    public void close() {
        server.shutdownNow();
    }
}
