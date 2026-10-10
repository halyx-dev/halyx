package org.kamranzafar.cirrus.benchmarks;

import org.kamranzafar.cirrus.interceptor.InterceptorChain;
import org.kamranzafar.cirrus.interceptor.RetryInterceptor;
import org.kamranzafar.cirrus.interceptor.RetryPolicy;
import org.kamranzafar.cirrus.storage.Blob;
import org.kamranzafar.cirrus.storage.BlobContent;
import org.kamranzafar.cirrus.storage.BlobKey;
import org.kamranzafar.cirrus.storage.BlobStore;
import org.kamranzafar.cirrus.storage.Checksums;
import org.kamranzafar.cirrus.storage.DefaultBlobStore;
import org.kamranzafar.cirrus.storage.GetOptions;
import org.kamranzafar.cirrus.storage.PutOptions;
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
 * What Cirrus itself costs per call, with no network: the portable layer (validation, interceptors,
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
