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
package org.kamranzafar.cirrus.aws;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.elasticache.ElastiCacheClient;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Floci with ElastiCache enabled: Floci starts real Valkey containers through the Docker socket and
 * proxies them on a fixed host port range, which therefore must be published as-is.
 */
final class ElastiCacheFloci {

    static final int FIRST_PROXY_PORT = 16379;
    static final int LAST_PROXY_PORT = 16384;

    private ElastiCacheFloci() {
    }

    static GenericContainer<?> container(Network network) {
        GenericContainer<?> floci = new GenericContainer<>(
                DockerImageName.parse(System.getProperty("floci.aws.image", "floci/floci:2.2.0")))
                .withNetwork(network)
                .withExposedPorts(4566)
                .withFileSystemBind("/var/run/docker.sock", "/var/run/docker.sock")
                .withEnv("FLOCI_SERVICES_ELASTICACHE_PROXY_BASE_PORT", String.valueOf(FIRST_PROXY_PORT))
                .withEnv("FLOCI_SERVICES_ELASTICACHE_PROXY_MAX_PORT", String.valueOf(LAST_PROXY_PORT))
                .waitingFor(Wait.forListeningPorts(4566));
        floci.withCreateContainerCmdModifier(cmd -> {
            cmd.withUser("root");
            List<ExposedPort> exposed = new ArrayList<>(List.of(cmd.getExposedPorts()));
            Ports bindings = cmd.getHostConfig().getPortBindings();
            for (int port = FIRST_PROXY_PORT; port <= LAST_PROXY_PORT; port++) {
                exposed.add(ExposedPort.tcp(port));
                bindings.add(PortBinding.parse(port + ":" + port));
            }
            cmd.withExposedPorts(exposed);
            cmd.getHostConfig().withPortBindings(bindings);
        });
        return floci;
    }

    static void configureDockerNetwork(GenericContainer<?> floci, Network network) {
        floci.withEnv("FLOCI_SERVICES_DOCKER_NETWORK", network.getId());
    }

    static URI endpoint(GenericContainer<?> floci) {
        return URI.create("http://" + floci.getHost() + ":" + floci.getMappedPort(4566));
    }

    static ElastiCacheClient client(GenericContainer<?> floci) {
        return ElastiCacheClient.builder()
                .endpointOverride(endpoint(floci))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
    }
}
