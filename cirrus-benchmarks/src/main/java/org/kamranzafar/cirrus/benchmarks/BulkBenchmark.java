package org.kamranzafar.cirrus.benchmarks;

import org.kamranzafar.cirrus.interceptor.InterceptorChain;
import org.kamranzafar.cirrus.storage.BlobKey;
import org.kamranzafar.cirrus.storage.BlobPage;
import org.kamranzafar.cirrus.storage.BlobStore;
import org.kamranzafar.cirrus.storage.DefaultBlobStore;
import org.kamranzafar.cirrus.storage.ListOptions;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * Listing and deleting many keys against a simulated network, compared with the naive sequential loop:
 * listAll prefetches the next page while the caller processes the current one, and deleteAll keeps
 * several requests in flight.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 1)
@Measurement(iterations = 3)
@Fork(1)
public class BulkBenchmark {

    @Param({"20"})
    public long latencyMillis;

    /** Simulated work the caller does per listed key, in microseconds. */
    @Param({"20"})
    public long workMicros;

    private SimulatedNetworkDriver driver;
    private BlobStore store;
    private final List<BlobKey> keys = new ArrayList<>();

    @Setup(Level.Invocation)
    public void setUp() {
        if (store == null) {
            driver = new SimulatedNetworkDriver(latencyMillis, 1_000_000_000L);
            store = new DefaultBlobStore("simulated", "bench", driver, InterceptorChain.empty());
            for (int i = 0; i < 5_000; i++) {
                keys.add(BlobKey.of(String.format("bulk/%05d", i)));
            }
        }
        keys.forEach(key -> driver.seed(key, new byte[1]));
    }

    @Benchmark
    public long listAllPrefetching(Blackhole hole) {
        return store.listAll("bulk/").peek(summary -> work(hole, summary)).count();
    }

    @Benchmark
    public long listSequentialPages(Blackhole hole) {
        long count = 0;
        Optional<String> token = Optional.empty();
        do {
            var options = token.map(ListOptions.prefix("bulk/")::withPageToken).orElse(ListOptions.prefix("bulk/"));
            BlobPage page = store.list(options);
            for (var summary : page.items()) {
                work(hole, summary);
                count++;
            }
            token = page.nextPageToken();
        } while (token.isPresent());
        return count;
    }

    @Benchmark
    public Object deleteAllConcurrent() {
        return store.deleteAll(keys.subList(0, 500));
    }

    @Benchmark
    public int deleteSequential() {
        keys.subList(0, 500).forEach(store::delete);
        return 500;
    }

    private void work(Blackhole hole, Object item) {
        LockSupport.parkNanos(workMicros * 1_000);
        hole.consume(item);
    }
}
