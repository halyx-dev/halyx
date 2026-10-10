package org.kamranzafar.cirrus.benchmarks;

import org.kamranzafar.cirrus.interceptor.InterceptorChain;
import org.kamranzafar.cirrus.storage.Blob;
import org.kamranzafar.cirrus.storage.BlobKey;
import org.kamranzafar.cirrus.storage.BlobStore;
import org.kamranzafar.cirrus.storage.DefaultBlobStore;
import org.kamranzafar.cirrus.storage.GetOptions;
import org.kamranzafar.cirrus.storage.PutOptions;
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
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Large transfers against a simulated network (round-trip latency plus a per-connection bandwidth cap):
 * how much parallel parts and ranges speed up one big upload or download.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 1)
@Measurement(iterations = 3)
@Fork(1)
public class TransferBenchmark {

    private static final int PART = 8 * 1024 * 1024;

    @Param({"64"})
    public int sizeMiB;

    @Param({"20"})
    public long latencyMillis;

    /** Bandwidth of one connection; parallel requests use several. */
    @Param({"100"})
    public long connectionMBps;

    @Param({"1", "4", "8"})
    public int parallelism;

    private BlobStore store;
    private byte[] content;
    private final BlobKey source = BlobKey.of("bench/source.bin");
    private final BlobKey target = BlobKey.of("bench/target.bin");

    @Setup
    public void setUp() {
        var driver = new SimulatedNetworkDriver(latencyMillis, connectionMBps * 1_000_000);
        store = new DefaultBlobStore("simulated", "bench", driver, InterceptorChain.empty());
        content = new byte[sizeMiB * 1024 * 1024];
        new Random(1).nextBytes(content);
        driver.seed(source, content);
    }

    @Benchmark
    public long download() throws IOException {
        var options = GetOptions.builder().parallelism(parallelism).partSize(PART).build();
        try (Blob blob = store.get(source, options)) {
            return blob.content().transferTo(OutputStream.nullOutputStream());
        }
    }

    @Benchmark
    public Object upload() {
        var options = PutOptions.builder().partSize(PART).uploadConcurrency(parallelism).build();
        return store.write(target, out -> out.write(content), options);
    }
}
