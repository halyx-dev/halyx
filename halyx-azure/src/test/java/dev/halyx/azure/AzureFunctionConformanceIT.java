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
package dev.halyx.azure;

import dev.halyx.Halyx;
import dev.halyx.config.Credentials;
import dev.halyx.config.ProviderConfig;
import dev.halyx.error.AccessDeniedException;
import dev.halyx.functions.FunctionConfig;
import dev.halyx.functions.FunctionInvoker;
import dev.halyx.functions.FunctionRequest;
import dev.halyx.tck.FunctionConformanceTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The function kit on the Azure Functions host in a container, serving the test function app with a function key
 * from its file-based secrets, so invocations log in with {@code azure.functions.key} as on Azure.
 */
@Testcontainers(disabledWithoutDocker = true)
class AzureFunctionConformanceIT extends FunctionConformanceTest {

    private static final String KEY = "halyx-tck-function-key";

    @Container
    static final GenericContainer<?> HOST = new GenericContainer<>(DockerImageName.parse(
            System.getProperty("azure.functions.image", "mcr.microsoft.com/azure-functions/python:4-python3.12")))
            .withEnv("AzureWebJobsScriptRoot", "/home/site/wwwroot")
            .withEnv("AzureFunctionsJobHost__Logging__Console__IsEnabled", "true")
            .withEnv("AzureWebJobsSecretStorageType", "files")
            .withEnv("FUNCTIONS_WORKER_RUNTIME", "python")
            .withCopyFileToContainer(MountableFile.forClasspathResource("functions/azure/"), "/home/site/wwwroot")
            .withCopyFileToContainer(MountableFile.forClasspathResource("functions/azure-secrets/host.json"),
                    "/azure-functions-host/Secrets/host.json")
            .withExposedPorts(80)
            .waitingFor(Wait.forHttp("/").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(3)));

    private static Halyx halyx;

    @BeforeAll
    static void start() {
        halyx = Halyx.builder().register("azure", ProviderConfig.of("azure").credentials(Credentials.anonymous())).build();
    }

    @AfterAll
    static void stop() {
        if (halyx != null) {
            halyx.close();
        }
    }

    private static String endpoint() {
        return "http://" + HOST.getHost() + ":" + HOST.getMappedPort(80);
    }

    @Override
    protected FunctionConfig.Builder function() {
        return FunctionConfig.builder("tck").property("azure.functions.endpoint", endpoint())
                .property("azure.functions.key", KEY);
    }

    @Override
    protected FunctionConfig.Builder missingFunction() {
        return FunctionConfig.builder("tck-missing").property("azure.functions.endpoint", endpoint())
                .property("azure.functions.key", KEY);
    }

    @Override
    protected FunctionInvoker invoker(FunctionConfig config) {
        return halyx.provider("azure").functionInvoker(config);
    }

    @Override
    protected long maxPayloadBytes() {
        return 100L * 1024 * 1024;
    }

    @Test
    void aWrongFunctionKeyIsRefused() {
        FunctionInvoker invoker = invoker(FunctionConfig.builder("tck").property("azure.functions.endpoint", endpoint())
                .property("azure.functions.key", "not-the-key").build());

        assertThatThrownBy(() -> invoker.invoke(FunctionRequest.of("{\"action\":\"echo\"}")))
                .isInstanceOf(AccessDeniedException.class);
    }
}
