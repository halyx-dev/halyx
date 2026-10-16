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
package dev.halyx.benchmarks;

import dev.halyx.Halyx;
import dev.halyx.config.ProviderConfig;
import dev.halyx.interceptor.InterceptorChain;
import dev.halyx.reactor.ReactiveBlobStore;
import dev.halyx.reactor.ReactiveHalyx;
import dev.halyx.storage.BlobKey;
import dev.halyx.storage.BlobMetadata;
import dev.halyx.storage.BlobStore;
import dev.halyx.storage.DefaultBlobStore;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * What halyx-reactor's adapters cost against blocking calls, on a simulated network: one call at a time (with no
 * latency this is the adapters' own overhead), and 100 calls fanned out, against a virtual-thread executor
 * running the blocking calls.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 2)
@Measurement(iterations = 3, time = 3)
@Fork(1)
public class ReactiveBenchmark {

    private static final int FAN_OUT = 100;
    private static final BlobKey KEY = BlobKey.of("bench/object");

    @Param({"0", "5"})
    public long latencyMillis;

    private BlobStore store;
    private Halyx halyx;
    private ReactiveHalyx virtualThreads;
    private ReactiveHalyx boundedElastic;
    private ReactiveBlobStore onVirtualThreads;
    private ReactiveBlobStore onBoundedElastic;
    private ExecutorService executor;

    @Setup
    public void setUp() {
        SimulatedNetworkDriver driver = new SimulatedNetworkDriver(latencyMillis, 1_000_000_000L);
        driver.seed(KEY, new byte[16]);
        store = new DefaultBlobStore("simulated", "bench", driver, InterceptorChain.empty());
        halyx = Halyx.builder().register("unused", ProviderConfig.of("memory")).build();
        virtualThreads = ReactiveHalyx.withVirtualThreads(halyx);
        boundedElastic = ReactiveHalyx.of(halyx, Schedulers.boundedElastic());
        onVirtualThreads = virtualThreads.wrap(store);
        onBoundedElastic = boundedElastic.wrap(store);
        executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @TearDown
    public void tearDown() {
        executor.shutdown();
        virtualThreads.close();
        halyx.close();
    }

    @Benchmark
    public Optional<BlobMetadata> oneBlocking() {
        return store.head(KEY);
    }

    @Benchmark
    public BlobMetadata oneReactiveOnVirtualThreads() {
        return onVirtualThreads.head(KEY).block();
    }

    @Benchmark
    public BlobMetadata oneReactiveOnBoundedElastic() {
        return onBoundedElastic.head(KEY).block();
    }

    @Benchmark
    public int fanOutBlockingOnVirtualThreads() throws Exception {
        List<Callable<Optional<BlobMetadata>>> calls = new ArrayList<>(FAN_OUT);
        for (int i = 0; i < FAN_OUT; i++) {
            calls.add(() -> store.head(KEY));
        }
        return executor.invokeAll(calls).size();
    }

    @Benchmark
    public Long fanOutReactiveOnVirtualThreads() {
        return Flux.range(0, FAN_OUT).flatMap(i -> onVirtualThreads.head(KEY), FAN_OUT).count().block();
    }

    @Benchmark
    public Long fanOutReactiveOnBoundedElastic() {
        return Flux.range(0, FAN_OUT).flatMap(i -> onBoundedElastic.head(KEY), FAN_OUT).count().block();
    }
}
