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

import dev.halyx.Halyx;
import dev.halyx.config.Credentials;
import dev.halyx.config.ProviderConfig;
import dev.halyx.functions.FunctionConfig;
import dev.halyx.functions.FunctionInvoker;
import dev.halyx.tck.FunctionConformanceTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The function kit on Google's Functions Framework in a container, as Cloud Run functions serves a function. The
 * framework serves every path, so another path stands in for a function that isn't deployed.
 */
@Testcontainers(disabledWithoutDocker = true)
class GcpFunctionConformanceIT extends FunctionConformanceTest {

    @Container
    static final GenericContainer<?> FRAMEWORK = new GenericContainer<>(
            new ImageFromDockerfile("halyx-tck-gcp-function", false)
                    .withFileFromClasspath("Dockerfile", "functions/gcp/Dockerfile")
                    .withFileFromClasspath("main.py", "functions/gcp/main.py")
                    .withBuildArg("BASE_IMAGE", System.getProperty("python.image", "python:3.12-slim")))
            .withExposedPorts(8080)
            .waitingFor(Wait.forListeningPort());

    private static Halyx halyx;

    @BeforeAll
    static void start() {
        halyx = Halyx.builder().register("gcp", ProviderConfig.of("gcp").credentials(Credentials.anonymous())).build();
    }

    @AfterAll
    static void stop() {
        if (halyx != null) {
            halyx.close();
        }
    }

    private static String url(String path) {
        return "http://" + FRAMEWORK.getHost() + ":" + FRAMEWORK.getMappedPort(8080) + path;
    }

    @Override
    protected FunctionConfig.Builder function() {
        return FunctionConfig.builder("tck").property("gcp.functions.url", url("/"));
    }

    @Override
    protected FunctionConfig.Builder missingFunction() {
        return FunctionConfig.builder("tck-missing").property("gcp.functions.url", url("/tck-missing"));
    }

    @Override
    protected FunctionInvoker invoker(FunctionConfig config) {
        return halyx.provider("gcp").functionInvoker(config);
    }

    @Override
    protected long maxPayloadBytes() {
        return 32L * 1024 * 1024;
    }
}
