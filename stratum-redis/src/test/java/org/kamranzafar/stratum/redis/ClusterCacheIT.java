package org.kamranzafar.stratum.redis;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.kamranzafar.stratum.CloudContext;
import org.kamranzafar.stratum.Stratum;
import org.kamranzafar.stratum.cache.Cache;
import org.kamranzafar.stratum.cache.CacheConfig;
import org.kamranzafar.stratum.config.ProviderConfig;
import org.kamranzafar.stratum.tck.CacheConformanceTest;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cache TCK on a three-master Valkey cluster (as Memorystore Cluster, ElastiCache cluster mode and
 * Azure Managed Redis with the OSS cluster policy run). Nodes announce 127.0.0.1 on fixed ports that
 * are published as-is, so the topology the client discovers is reachable from the host.
 */
@Testcontainers(disabledWithoutDocker = true)
class ClusterCacheIT {

    private static final int[] PORTS = {17000, 17001, 17002};
    private static final String START_CLUSTER = String.join(" && ",
            "for p in 17000 17001 17002; do valkey-server --port $p --cluster-enabled yes --cluster-config-file nodes-$p.conf"
                    + " --cluster-announce-ip 127.0.0.1 --save '' --appendonly no --daemonize yes; done",
            "sleep 1",
            "valkey-cli --cluster create 127.0.0.1:17000 127.0.0.1:17001 127.0.0.1:17002 --cluster-replicas 0 --cluster-yes",
            "tail -f /dev/null");

    private static GenericContainer<?> cluster;
    private static Stratum stratum;

    @BeforeAll
    static void start() {
        cluster = new GenericContainer<>(ValkeyCacheConformanceIT.VALKEY)
                .withCommand("sh", "-c", START_CLUSTER)
                .withCreateContainerCmdModifier(cmd -> {
                    List<ExposedPort> exposed = new ArrayList<>();
                    Ports bindings = new Ports();
                    for (int port : PORTS) {
                        exposed.add(ExposedPort.tcp(port));
                        bindings.add(PortBinding.parse(port + ":" + port));
                    }
                    cmd.withExposedPorts(exposed);
                    cmd.getHostConfig().withPortBindings(bindings);
                })
                .waitingFor(Wait.forSuccessfulCommand("valkey-cli -p 17000 cluster info | grep -q cluster_state:ok")
                        .withStartupTimeout(Duration.ofMinutes(1)));
        cluster.start();
        stratum = Stratum.builder()
                .plugin(new RedisTestProvider(RedisCaches.iamUnsupported("redis-test")))
                .register("redis", ProviderConfig.of("redis-test"))
                .build();
    }

    @AfterAll
    static void stop() {
        if (stratum != null) {
            stratum.close();
        }
        if (cluster != null) {
            cluster.stop();
        }
    }

    private static CacheConfig.Builder clusterConfig() {
        return CacheConfig.builder(URI.create("redis://127.0.0.1:" + PORTS[0])).cluster(true);
    }

    @Nested
    class OnACluster extends CacheConformanceTest {
        @Override
        protected CloudContext context() {
            return stratum.provider("redis");
        }

        @Override
        protected CacheConfig.Builder config() {
            return clusterConfig();
        }
    }

    @Test
    void keysAreShardedAcrossAllMasters() throws Exception {
        Cache<String> cache = stratum.provider("redis").cache(clusterConfig().namespace("sharding").build());
        Map<String, String> entries = new LinkedHashMap<>();
        for (int i = 0; i < 300; i++) {
            entries.put("k" + i, "v" + i);
        }
        cache.putAll(entries);

        for (int port : PORTS) {
            String dbsize = cluster.execInContainer("valkey-cli", "-p", String.valueOf(port), "dbsize").getStdout().trim();
            assertThat(Long.parseLong(dbsize)).as("keys on node %d", port).isPositive();
        }
        assertThat(cache.getAll(entries.keySet())).hasSize(300);
        assertThat(cache.clear()).isEqualTo(300);
    }
}
