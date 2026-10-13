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

import dev.halyx.interceptor.InterceptorChain;
import dev.halyx.interceptor.RetryInterceptor;
import dev.halyx.interceptor.RetryPolicy;
import dev.halyx.storage.Blob;
import dev.halyx.storage.BlobContent;
import dev.halyx.storage.BlobKey;
import dev.halyx.storage.BlobStore;
import dev.halyx.storage.Checksums;
import dev.halyx.storage.DefaultBlobStore;
import dev.halyx.storage.GetOptions;
import dev.halyx.storage.PutOptions;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * What Halyx itself costs per call, with no network: the portable layer (validation, interceptors,
 * error translation) over a zero-latency driver, against calling the driver directly.
 */
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
public class CoreBenchmark {

    private final byte[] small = new byte[1024];
    private final BlobKey key = BlobKey.of("bench/small.bin");
    private BlobStore store;
    private SimulatedNetworkDriver driver;

    @Setup
    public void setUp() {
        driver = new SimulatedNetworkDriver(0, Long.MAX_VALUE / 1_000_000_000L);
        store = new DefaultBlobStore("simulated", "bench", driver,
                new InterceptorChain(List.of(new RetryInterceptor(RetryPolicy.defaults()))));
        driver.seed(key, small);
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public Object putSmallThroughStore() {
        return store.put(key, BlobContent.ofBytes(small), PutOptions.defaults());
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public Object putSmallDriverOnly() {
        return driver.put(key, new BlobContent.OfBytes(small), PutOptions.defaults(), Checksums.none());
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public byte[] getSmallThroughStore() {
        try (Blob blob = store.get(key, GetOptions.defaults())) {
            return blob.readAllBytes();
        }
    }
}
