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
package dev.halyx.stream;

import dev.halyx.capability.Capabilities;
import dev.halyx.interceptor.InterceptorChain;
import dev.halyx.interceptor.OperationContext;
import dev.halyx.messaging.ClaimCheck;
import dev.halyx.storage.BlobKey;
import dev.halyx.stream.spi.ConsumerDriver;
import dev.halyx.stream.spi.DriverRecord;
import dev.halyx.stream.spi.Partition;
import dev.halyx.stream.spi.RebalanceListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** The portable stream consumer: claim-check resolution, manual commits and interceptors over a driver. */
public final class DefaultStreamConsumer implements StreamConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultStreamConsumer.class);

    private final ConsumerDriver driver;
    private final StreamConsumerConfig config;
    private final InterceptorChain interceptors;
    /** The next offset to commit per partition, for manual polling; used on the polling thread only. */
    private final Map<Partition, Long> positions = new HashMap<>();
    private boolean polling;
    private PartitionListener listener;
    private boolean closed;

    public DefaultStreamConsumer(ConsumerDriver driver, StreamConsumerConfig config, InterceptorChain interceptors) {
        this.driver = Objects.requireNonNull(driver, "driver");
        this.config = Objects.requireNonNull(config, "config");
        this.interceptors = Objects.requireNonNull(interceptors, "interceptors");
    }

    @Override
    public String provider() {
        return driver.provider();
    }

    @Override
    public String topic() {
        return driver.topic();
    }

    @Override
    public String group() {
        return driver.group();
    }

    @Override
    public Capabilities capabilities() {
        return driver.capabilities();
    }

    @Override
    public List<ReceivedRecord> poll(Duration wait) {
        Objects.requireNonNull(wait, "wait");
        startPolling();
        List<DriverRecord> raw = execute("poll", true, () -> driver.poll(wait));
        List<ReceivedRecord> records = new ArrayList<>(raw.size());
        for (DriverRecord record : raw) {
            records.add(toReceived(record, 1));
            positions.put(record.partition(), record.offset() + 1);
        }
        return records;
    }

    private synchronized void startPolling() {
        if (closed) {
            throw new IllegalStateException(this + " is closed");
        }
        if (listener != null) {
            throw new IllegalStateException(this + " is running a listener; poll and listen can't be mixed");
        }
        if (!polling) {
            driver.subscribe(new RebalanceListener() {
                @Override
                public void onAssigned(Collection<Partition> partitions) {
                }

                /** Only {@link #commit()} commits: records polled but not committed are read again by the new owner. */
                @Override
                public void onRevoked(Collection<Partition> partitions) {
                    partitions.forEach(positions::remove);
                }
            });
            polling = true;
        }
    }

    @Override
    public void commit() {
        if (!positions.isEmpty()) {
            commitNow(Map.copyOf(positions));
        }
    }

    void commitNow(Map<Partition, Long> nextOffsets) {
        execute("commit", true, () -> {
            driver.commit(nextOffsets);
            return null;
        });
    }

    @Override
    public synchronized StreamListener listen(RecordListener recordListener, ListenerOptions options) {
        Objects.requireNonNull(recordListener, "listener");
        Objects.requireNonNull(options, "options");
        if (closed) {
            throw new IllegalStateException(this + " is closed");
        }
        if (polling) {
            throw new IllegalStateException(this + " is being polled by hand; poll and listen can't be mixed");
        }
        if (listener != null && listener.isRunning()) {
            throw new IllegalStateException(this + " already has a running listener");
        }
        listener = new PartitionListener(this, recordListener, options);
        return listener;
    }

    ConsumerDriver driver() {
        return driver;
    }

    ReceivedRecord toReceived(DriverRecord record, int attempt) {
        byte[] value = record.value();
        Map<String, String> headers = record.headers();
        String claim = headers.get(ClaimCheck.ATTRIBUTE);
        if (claim != null && config.claimCheck().isPresent()) {
            value = execute("claimCheck", true, () -> config.claimCheck().get().store().getBytes(BlobKey.of(claim)));
            headers = new LinkedHashMap<>(headers);
            headers.remove(ClaimCheck.ATTRIBUTE);
        } else if (claim != null) {
            LOG.warn("Record {}@{} has a claim check ({}) but the consumer has no ClaimCheck configured; "
                    + "delivering the pointer as the value", record.partition(), record.offset(), claim);
        }
        return new ReceivedRecord(record.partition().topic(), record.partition().partition(), record.offset(),
                record.key(), value, headers, record.timestamp(), attempt);
    }

    <T> T execute(String operation, boolean idempotent, Supplier<T> call) {
        OperationContext context = new OperationContext(DefaultStreamProducer.SERVICE, operation, provider(),
                topic() + "/" + group(), idempotent);
        return interceptors.execute(context, () -> {
            try {
                return call.get();
            } catch (RuntimeException e) {
                throw driver.translate(e);
            }
        });
    }

    @Override
    public <T> T unwrap(Class<T> nativeType) {
        return driver.unwrap(Objects.requireNonNull(nativeType, "nativeType"));
    }

    @Override
    public void close() {
        PartitionListener running;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            running = listener;
        }
        try {
            if (running != null) {
                running.close();
            }
        } finally {
            try {
                driver.close();
            } catch (RuntimeException e) {
                LOG.warn("Could not close {} cleanly", this, e);
            }
        }
    }

    @Override
    public String toString() {
        return "StreamConsumer[" + provider() + ":" + topic() + "/" + group() + "]";
    }
}
