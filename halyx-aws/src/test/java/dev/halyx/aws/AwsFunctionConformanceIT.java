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
package dev.halyx.aws;

import dev.halyx.Halyx;
import dev.halyx.config.Credentials;
import dev.halyx.config.ProviderConfig;
import dev.halyx.functions.FunctionConfig;
import dev.halyx.functions.FunctionInvoker;
import dev.halyx.tck.FunctionConformanceTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.Runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The function kit on Lambda emulated by Floci, which runs functions in containers from AWS's Lambda runtime images:
 * Floci needs the Docker socket, and a network its function containers share with it.
 */
@Testcontainers(disabledWithoutDocker = true)
class AwsFunctionConformanceIT extends FunctionConformanceTest {

    private static final Network NETWORK = Network.newNetwork();

    @Container
    static final GenericContainer<?> FLOCI = new GenericContainer<>(
            DockerImageName.parse(System.getProperty("floci.aws.image", "floci/floci:2.2.0")))
            .withNetwork(NETWORK)
            .withNetworkAliases("floci")
            .withFileSystemBind("/var/run/docker.sock", "/var/run/docker.sock")
            .withEnv("FLOCI_SERVICES_LAMBDA_DOCKER_NETWORK", NETWORK.getId())
            .withEnv("FLOCI_SERVICES_LAMBDA_DOCKER_HOST_OVERRIDE", "floci")
            .withEnv("FLOCI_SERVICES_LAMBDA_INIT_TIMEOUT_SECONDS", "60")
            .withExposedPorts(4566)
            .waitingFor(Wait.forListeningPort());

    private static Halyx halyx;

    @BeforeAll
    static void start() {
        URI endpoint = URI.create("http://" + FLOCI.getHost() + ":" + FLOCI.getMappedPort(4566));
        try (LambdaClient lambda = LambdaClient.builder().region(Region.US_EAST_1).endpointOverride(endpoint)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build()) {
            lambda.createFunction(b -> b.functionName("halyx-tck").runtime(Runtime.PYTHON3_13).handler("tck.handler")
                    .role("arn:aws:iam::000000000000:role/halyx-tck").timeout(60)
                    .code(c -> c.zipFile(SdkBytes.fromByteArray(zip("functions/lambda/tck.py", "tck.py")))));
            lambda.waiter().waitUntilFunctionActiveV2(b -> b.functionName("halyx-tck"));
            // One instance, so the counts the function keeps in memory are the same for every invocation
            lambda.putFunctionConcurrency(b -> b.functionName("halyx-tck").reservedConcurrentExecutions(1));
        }
        halyx = Halyx.builder()
                .register("aws", ProviderConfig.of("aws").region("us-east-1").endpoint(endpoint)
                        .credentials(Credentials.of("test", "test")))
                .build();
    }

    @AfterAll
    static void stop() {
        if (halyx != null) {
            halyx.close();
        }
    }

    /** A deployment package holding one classpath resource. */
    static byte[] zip(String resource, String entry) {
        try (InputStream source = AwsFunctionConformanceIT.class.getClassLoader().getResourceAsStream(resource)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
                zip.putNextEntry(new ZipEntry(entry));
                source.transferTo(zip);
                zip.closeEntry();
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    protected FunctionConfig.Builder function() {
        return FunctionConfig.builder("halyx-tck");
    }

    @Override
    protected FunctionConfig.Builder missingFunction() {
        return FunctionConfig.builder("halyx-tck-missing");
    }

    @Override
    protected FunctionInvoker invoker(FunctionConfig config) {
        return halyx.provider("aws").functionInvoker(config);
    }

    @Override
    protected long maxPayloadBytes() {
        return 6L * 1024 * 1024;
    }

    @Override
    protected long maxHeaderBytes() {
        return 3_583;
    }

    /** Lambda functions return a payload, not an HTTP status. */
    @Override
    protected boolean returnsHttpStatuses() {
        return false;
    }
}
