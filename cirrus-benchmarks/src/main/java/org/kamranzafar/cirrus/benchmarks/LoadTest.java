package org.kamranzafar.cirrus.benchmarks;

import org.kamranzafar.cirrus.Cirrus;
import org.kamranzafar.cirrus.config.Credentials;
import org.kamranzafar.cirrus.config.ProviderConfig;
import org.kamranzafar.cirrus.storage.Blob;
import org.kamranzafar.cirrus.storage.BlobContent;
import org.kamranzafar.cirrus.storage.BlobKey;
import org.kamranzafar.cirrus.storage.BlobStore;
import org.kamranzafar.cirrus.storage.GetOptions;
import org.kamranzafar.cirrus.storage.PutOptions;

import java.io.OutputStream;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * A load test against a real bucket: {@code concurrency} virtual threads run a mix of puts, gets and heads
 * for {@code duration}, then it prints throughput and latency percentiles per operation. Emulators don't
 * behave like cloud storage under load, so tune against the real thing.
 *
 * <pre>
 * java -cp cirrus-benchmarks/target/benchmarks.jar org.kamranzafar.cirrus.benchmarks.LoadTest \
 *     -Dprovider=aws -Dbucket=my-bench-bucket -Dregion=eu-west-1 \
 *     -Dconcurrency=64 -Dduration=60s -Dsize=1MiB -Dmix=put:20,get:70,head:10 \
 *     -Dcirrus.http.max-connections=256
 * </pre>
 *
 * Properties: {@code provider} (aws, azure, gcp), {@code bucket}, {@code region}, {@code endpoint},
 * {@code concurrency} (64), {@code duration} (60s), {@code size} (1MiB; {@code KiB}/{@code MiB} suffixes),
 * {@code keys} (objects written up front for gets, 200), {@code mix} (put:20,get:70,head:10),
 * {@code parallelism} (ranges per get, 1), {@code cleanup} (true). Any {@code cirrus.*} property is passed
 * to the provider without the prefix, e.g. {@code -Dcirrus.aws.retry-mode=standard}. Credentials come from
 * each cloud's default chain.
 */
public final class LoadTest {

    private enum Op { PUT, GET, HEAD }

    /** Latencies of one operation type, in microseconds; bounded so a long run can't exhaust memory. */
    private static final class Recorder {
        private static final int CAPACITY = 2_000_000;
        private final long[] samples = new long[CAPACITY];
        private final AtomicLong count = new AtomicLong();
        private final LongAdder bytes = new LongAdder();
        private final Map<String, LongAdder> errors = new ConcurrentHashMap<>();

        void record(long micros, long transferred) {
            long i = count.getAndIncrement();
            samples[(int) (i % CAPACITY)] = micros;
            bytes.add(transferred);
        }

        void error(Throwable e) {
            errors.computeIfAbsent(e.getClass().getSimpleName(), k -> new LongAdder()).increment();
        }
    }

    public static void main(String[] args) throws InterruptedException {
        String provider = required("provider");
        String bucket = required("bucket");
        int concurrency = Integer.getInteger("concurrency", 64);
        Duration duration = duration(System.getProperty("duration", "60s"));
        int size = (int) bytes(System.getProperty("size", "1MiB"));
        int keyCount = Integer.getInteger("keys", 200);
        int parallelism = Integer.getInteger("parallelism", 1);
        Map<Op, Integer> mix = mix(System.getProperty("mix", "put:20,get:70,head:10"));

        ProviderConfig config = ProviderConfig.of(provider).credentials(Credentials.defaultChain());
        if (System.getProperty("region") != null) {
            config = config.region(System.getProperty("region"));
        }
        if (System.getProperty("endpoint") != null) {
            config = config.endpoint(URI.create(System.getProperty("endpoint")));
        }
        for (String name : System.getProperties().stringPropertyNames()) {
            if (name.startsWith("cirrus.")) {
                config = config.property(name.substring("cirrus.".length()), System.getProperty(name));
            }
        }

        String prefix = "cirrus-loadtest/" + UUID.randomUUID() + "/";
        byte[] payload = new byte[size];
        new Random(7).nextBytes(payload);
        List<BlobKey> keys = new ArrayList<>();
        for (int i = 0; i < keyCount; i++) {
            keys.add(BlobKey.of(prefix + "seed-" + i));
        }

        try (Cirrus cirrus = Cirrus.builder().register("target", config).build()) {
            BlobStore store = cirrus.provider("target").blobStore(bucket);
            System.out.printf(Locale.ROOT, "Seeding %d x %s objects under %s%s%n", keyCount, human(size), bucket, "/" + prefix);
            seed(store, keys, payload, concurrency);

            Map<Op, Recorder> recorders = new LinkedHashMap<>();
            for (Op op : Op.values()) {
                recorders.put(op, new Recorder());
            }
            GetOptions getOptions = GetOptions.builder().parallelism(parallelism).build();
            long deadline = System.nanoTime() + duration.toNanos();
            int totalWeight = mix.values().stream().mapToInt(Integer::intValue).sum();
            System.out.printf(Locale.ROOT, "Running %s with %d virtual threads, mix %s%n", duration, concurrency, mix);

            List<Thread> workers = new ArrayList<>();
            for (int t = 0; t < concurrency; t++) {
                int worker = t;
                workers.add(Thread.ofVirtual().name("load-" + t).start(() -> {
                    long sequence = 0;
                    while (System.nanoTime() < deadline) {
                        Op op = pick(mix, totalWeight);
                        Recorder recorder = recorders.get(op);
                        long started = System.nanoTime();
                        try {
                            long transferred = switch (op) {
                                case PUT -> {
                                    store.put(BlobKey.of(prefix + "w-" + worker + "-" + (sequence++ % 50)),
                                            BlobContent.ofBytes(payload), PutOptions.defaults());
                                    yield size;
                                }
                                case GET -> {
                                    try (Blob blob = store.get(randomKey(keys), getOptions)) {
                                        yield blob.content().transferTo(OutputStream.nullOutputStream());
                                    }
                                }
                                case HEAD -> {
                                    store.head(randomKey(keys));
                                    yield 0;
                                }
                            };
                            recorder.record((System.nanoTime() - started) / 1_000, transferred);
                        } catch (Exception e) {
                            recorder.error(e);
                        }
                    }
                }));
            }
            for (Thread worker : workers) {
                worker.join();
            }
            report(recorders, duration);

            if (Boolean.parseBoolean(System.getProperty("cleanup", "true"))) {
                var leftovers = store.listAll(prefix).map(s -> s.key()).toList();
                var result = store.deleteAll(leftovers);
                System.out.printf(Locale.ROOT, "Cleaned up %d objects (%d failed)%n",
                        result.deleted().size(), result.failed().size());
            }
        }
    }

    private static void seed(BlobStore store, List<BlobKey> keys, byte[] payload, int concurrency)
            throws InterruptedException {
        var next = new AtomicLong();
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < Math.min(concurrency, keys.size()); t++) {
            threads.add(Thread.ofVirtual().start(() -> {
                for (long i; (i = next.getAndIncrement()) < keys.size(); ) {
                    store.put(keys.get((int) i), BlobContent.ofBytes(payload));
                }
            }));
        }
        for (Thread thread : threads) {
            thread.join();
        }
    }

    private static void report(Map<Op, Recorder> recorders, Duration duration) {
        double seconds = duration.toMillis() / 1000.0;
        System.out.printf(Locale.ROOT, "%n%-5s %10s %10s %10s %9s %9s %9s %9s  %s%n",
                "op", "count", "ops/s", "MB/s", "p50 ms", "p90 ms", "p99 ms", "max ms", "errors");
        recorders.forEach((op, r) -> {
            int n = (int) Math.min(r.count.get(), Recorder.CAPACITY);
            if (n == 0 && r.errors.isEmpty()) {
                return;
            }
            long[] sorted = Arrays.copyOf(r.samples, n);
            Arrays.sort(sorted);
            System.out.printf(Locale.ROOT, "%-5s %10d %10.1f %10.1f %9.1f %9.1f %9.1f %9.1f  %s%n",
                    op.name().toLowerCase(Locale.ROOT), r.count.get(), r.count.get() / seconds,
                    r.bytes.sum() / seconds / 1_000_000, percentile(sorted, 50), percentile(sorted, 90),
                    percentile(sorted, 99), n == 0 ? 0 : sorted[n - 1] / 1000.0, r.errors);
        });
    }

    private static double percentile(long[] sorted, int p) {
        if (sorted.length == 0) {
            return 0;
        }
        int index = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, index)] / 1000.0;
    }

    private static Op pick(Map<Op, Integer> mix, int totalWeight) {
        int roll = ThreadLocalRandom.current().nextInt(totalWeight);
        for (var entry : mix.entrySet()) {
            roll -= entry.getValue();
            if (roll < 0) {
                return entry.getKey();
            }
        }
        throw new IllegalStateException();
    }

    private static BlobKey randomKey(List<BlobKey> keys) {
        return keys.get(ThreadLocalRandom.current().nextInt(keys.size()));
    }

    private static Map<Op, Integer> mix(String spec) {
        Map<Op, Integer> mix = new LinkedHashMap<>();
        for (String part : spec.split(",")) {
            String[] kv = part.trim().split(":");
            int weight = Integer.parseInt(kv[1].trim());
            if (weight > 0) {
                mix.put(Op.valueOf(kv[0].trim().toUpperCase(Locale.ROOT)), weight);
            }
        }
        if (mix.isEmpty()) {
            throw new IllegalArgumentException("mix needs at least one positive weight: " + spec);
        }
        return mix;
    }

    private static long bytes(String value) {
        String v = value.trim().toLowerCase(Locale.ROOT);
        if (v.endsWith("mib")) {
            return Long.parseLong(v.substring(0, v.length() - 3).trim()) * 1024 * 1024;
        }
        if (v.endsWith("kib")) {
            return Long.parseLong(v.substring(0, v.length() - 3).trim()) * 1024;
        }
        return Long.parseLong(v);
    }

    private static Duration duration(String value) {
        String v = value.trim().toLowerCase(Locale.ROOT);
        if (v.endsWith("m")) {
            return Duration.ofMinutes(Long.parseLong(v.substring(0, v.length() - 1)));
        }
        return Duration.ofSeconds(Long.parseLong(v.endsWith("s") ? v.substring(0, v.length() - 1) : v));
    }

    private static String human(long bytes) {
        return bytes >= 1024 * 1024 ? bytes / (1024 * 1024) + " MiB" : bytes >= 1024 ? bytes / 1024 + " KiB" : bytes + " B";
    }

    private static String required(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Set -D" + name);
        }
        return value;
    }

    private LoadTest() {
    }
}
