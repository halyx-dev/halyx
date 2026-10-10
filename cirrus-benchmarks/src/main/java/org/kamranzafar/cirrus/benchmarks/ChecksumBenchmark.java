package org.kamranzafar.cirrus.benchmarks;

import org.kamranzafar.cirrus.storage.ChecksumAlgorithm;
import org.kamranzafar.cirrus.storage.Checksums;
import org.kamranzafar.cirrus.storage.spi.ChecksumCalculator;
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

import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** The checksum algorithms used to validate transfers, over one 8 MiB part; ops/s x 8 = MiB/s. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
public class ChecksumBenchmark {

    @Param({"CRC32C", "CRC32", "MD5"})
    public ChecksumAlgorithm algorithm;

    private final byte[] part = new byte[8 * 1024 * 1024];

    @Setup
    public void setUp() {
        new Random(2).nextBytes(part);
    }

    @Benchmark
    public Checksums checksumPart() {
        var calculator = new ChecksumCalculator(Set.of(algorithm));
        calculator.update(part, 0, part.length);
        return calculator.result();
    }
}
