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

import dev.halyx.storage.ChecksumAlgorithm;
import dev.halyx.storage.Checksums;
import dev.halyx.storage.spi.ChecksumCalculator;
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
