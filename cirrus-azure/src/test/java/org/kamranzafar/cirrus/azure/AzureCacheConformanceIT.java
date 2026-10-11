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
package org.kamranzafar.cirrus.azure;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.kamranzafar.cirrus.CloudContext;
import org.kamranzafar.cirrus.Cirrus;
import org.kamranzafar.cirrus.cache.CacheAuth;
import org.kamranzafar.cirrus.cache.CacheConfig;
import org.kamranzafar.cirrus.config.ProviderConfig;
import org.kamranzafar.cirrus.error.AccessDeniedException;
import org.kamranzafar.cirrus.tck.CacheConformanceTest;
import com.github.dockerjava.api.model.ExposedPort;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The cache TCK through the Azure provider on Azure Cache for Redis emulated by Floci-az: a real
 * Valkey cache provisioned through ARM, authenticated with its primary access key. (Floci-az does not
 * emulate Entra ID logins to Redis; Entra tokens are unit-tested in EntraIdRedisTokensTest.)
 */
@Testcontainers(disabledWithoutDocker = true)
class AzureCacheConformanceIT {

    private static final int FIRST_PORT = 16390;
    private static final int LAST_PORT = 16395;
    private static final String CACHE_PATH =
            "/subscriptions/cirrus-sub/resourceGroups/cirrus-rg/providers/Microsoft.Cache/redis/cirrus-tck";
    private static final String API_VERSION = "?api-version=2024-11-01";

    private static final Network NETWORK = Network.newNetwork();

    /** Floci-az reaches the cache containers it starts by name, so it must share their Docker network. */
    private static final GenericContainer<?> FLOCI = new GenericContainer<>(
            DockerImageName.parse(System.getProperty("floci.azure.image", "floci/floci-az:0.14.0")))
            .withNetwork(NETWORK)
            .withExposedPorts(4577)
            .withFileSystemBind("/var/run/docker.sock", "/var/run/docker.sock")
            .withEnv("FLOCI_AZ_SERVICES_REDIS_BASE_PORT", String.valueOf(FIRST_PORT))
            .withEnv("FLOCI_AZ_SERVICES_REDIS_MAX_PORT", String.valueOf(LAST_PORT))
            .withCreateContainerCmdModifier(cmd -> cmd.withUser("root"))
            .waitingFor(Wait.forListeningPort());

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static Cirrus cirrus;
    private static URI cacheEndpoint;
    private static String primaryKey;

    @BeforeAll
    static void start() throws Exception {
        FLOCI.withEnv("FLOCI_AZ_SERVICES_DOCKER_NETWORK", NETWORK.getId());
        FLOCI.start();
        arm("PUT", CACHE_PATH, """
                {"location": "eastus",
                 "properties": {"sku": {"name": "Basic", "family": "C", "capacity": 0}, "enableNonSslPort": true}}""");
        String cache = awaitProvisioned();
        int port = publishedPort(field(cache, "\"hostName\"\\s*:\\s*\"([^\"]+)\""));
        primaryKey = field(arm("POST", CACHE_PATH + "/listKeys", "{}"), "\"primaryKey\"\\s*:\\s*\"([^\"]+)\"");
        cacheEndpoint = URI.create("redis://" + FLOCI.getHost() + ":" + port);
        cirrus = Cirrus.builder().register("azure", ProviderConfig.of("azure")).build();
    }

    @AfterAll
    static void stop() throws Exception {
        if (cirrus != null) {
            cirrus.close();
        }
        if (FLOCI.isRunning()) {
            arm("DELETE", CACHE_PATH, null);
            FLOCI.stop();
        }
        NETWORK.close();
    }

    /** The host port Docker published for the cache container's 6379. */
    private static int publishedPort(String containerName) {
        var bindings = DockerClientFactory.instance().client().inspectContainerCmd(containerName).exec()
                .getNetworkSettings().getPorts().getBindings().get(ExposedPort.tcp(6379));
        assertThat(bindings).as("published ports of %s", containerName).isNotEmpty();
        return Integer.parseInt(bindings[0].getHostPortSpec());
    }

    private static String awaitProvisioned() throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofMinutes(2));
        while (true) {
            String cache = arm("GET", CACHE_PATH, null);
            if (cache.contains("\"provisioningState\":\"Succeeded\"") || cache.contains("\"provisioningState\" : \"Succeeded\"")) {
                return cache;
            }
            assertThat(Instant.now()).as("cache provisioning: %s", cache).isBefore(deadline);
            Thread.sleep(500);
        }
    }

    private static String arm(String method, String path, String body) throws Exception {
        URI uri = URI.create("http://" + FLOCI.getHost() + ":" + FLOCI.getMappedPort(4577) + path + API_VERSION);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("%s %s: %s", method, path, response.body()).isLessThan(300);
        return response.body();
    }

    private static String field(String json, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(json);
        assertThat(matcher.find()).as("%s in %s", regex, json).isTrue();
        return matcher.group(1);
    }

    @Nested
    class WithAccessKey extends CacheConformanceTest {
        @Override
        protected CloudContext context() {
            return cirrus.provider("azure");
        }

        @Override
        protected CacheConfig.Builder config() {
            return CacheConfig.builder(cacheEndpoint).auth(CacheAuth.password(primaryKey));
        }
    }

    @Test
    void wrongAccessKeyIsRejected() {
        var wrong = CacheConfig.builder(cacheEndpoint).auth(CacheAuth.password("not-the-key")).build();

        assertThatThrownBy(() -> cirrus.provider("azure").cache(wrong).get("k")).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void entraIdNeedsTheDefaultCredentialChain() {
        Cirrus keyed = Cirrus.builder().register("azure", ProviderConfig.of("azure")
                .credentials(org.kamranzafar.cirrus.config.Credentials.of("account", "key"))).build();
        try (keyed) {
            var entra = CacheConfig.builder(cacheEndpoint).auth(CacheAuth.iam()).build();
            assertThatThrownBy(() -> keyed.provider("azure").cache(entra)).hasMessageContaining("default Azure credential chain");
        }
    }
}
