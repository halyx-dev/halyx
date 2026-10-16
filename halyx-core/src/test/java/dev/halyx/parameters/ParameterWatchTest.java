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
package dev.halyx.parameters;

import dev.halyx.capability.Capabilities;
import dev.halyx.error.CloudException;
import dev.halyx.error.UnavailableException;
import dev.halyx.interceptor.InterceptorChain;
import dev.halyx.parameters.spi.ParameterDriver;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A watch over a backend whose listings lag its writes and go back and forth, as SSM's GetParametersByPath
 * does for a second or two after each write, while point reads are current.
 */
class ParameterWatchTest {

    /** Point reads see {@code current}; every other listing is the {@code stale} snapshot. */
    static final class LaggingDriver implements ParameterDriver {
        final Map<String, Parameter> current = new ConcurrentHashMap<>();
        volatile Map<String, Parameter> stale = Map.of();
        private int listings;

        void put(String name, String value, int version) {
            current.put(name, new Parameter(name, value, String.valueOf(version), Instant.ofEpochSecond(version)));
        }

        @Override
        public Optional<Parameter> get(String name) {
            return Optional.ofNullable(current.get(name));
        }

        @Override
        public Optional<Parameter> get(String name, String version) {
            return get(name).filter(p -> p.version().equals(version));
        }

        @Override
        public synchronized Map<String, Parameter> list(String prefix) {
            return listings++ % 2 == 0 ? Map.copyOf(current) : stale;
        }

        @Override
        public Parameter put(String name, String value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Capabilities capabilities() {
            return Capabilities.none();
        }

        @Override
        public CloudException translate(Throwable error) {
            return new UnavailableException(error.getMessage());
        }

        @Override
        public <T> T unwrap(Class<T> nativeType) {
            throw new IllegalArgumentException();
        }
    }

    private final LaggingDriver driver = new LaggingDriver();
    private final ParameterStore store = new DefaultParameterStore("test",
            ParameterStoreConfig.parameters().prefix("app").build(), driver, InterceptorChain.empty());
    private final BlockingQueue<ParameterChanges> events = new LinkedBlockingQueue<>();

    @Test
    void staleListingsReportNothing() throws Exception {
        driver.put("app/a", "1-rotated", 2);
        driver.put("app/c", "3", 1);
        // an older replica: a's previous version, b not yet deleted, c not yet written
        driver.stale = Map.of("app/a", new Parameter("app/a", "1", "1", Instant.ofEpochSecond(1)),
                "app/b", new Parameter("app/b", "2", "1", Instant.ofEpochSecond(1)));

        try (AutoCloseable watch = store.watch("", Duration.ofMillis(10), events::add)) {
            Thread.sleep(300);
        }

        assertThat(events).as("the stale listing disagrees with reads every other poll, and reads win").isEmpty();
    }

    @Test
    void changesAreReportedOnceThoughListingsGoBackAndForth() throws Exception {
        driver.put("app/a", "1", 1);
        driver.stale = Map.copyOf(driver.current);
        List<ParameterChanges> seen = new ArrayList<>();
        try (AutoCloseable watch = store.watch("", Duration.ofMillis(10), events::add)) {
            driver.put("app/b", "2", 1);
            seen.add(events.poll(5, TimeUnit.SECONDS));

            driver.put("app/a", "1-rotated", 2);
            seen.add(events.poll(5, TimeUnit.SECONDS));

            driver.current.remove("app/b");
            seen.add(events.poll(5, TimeUnit.SECONDS));

            Thread.sleep(200);   // the stale listing keeps alternating with the current one
        }

        assertThat(seen.get(0).added()).containsOnlyKeys("b");
        assertThat(seen.get(0).updated()).isEmpty();
        assertThat(seen.get(0).removed()).isEmpty();
        assertThat(seen.get(1).updated()).containsOnlyKeys("a");
        assertThat(seen.get(1).updated().get("a").value()).isEqualTo("1-rotated");
        assertThat(seen.get(1).added()).isEmpty();
        assertThat(seen.get(1).removed()).isEmpty();
        assertThat(seen.get(2).removed()).containsExactly("b");
        assertThat(seen.get(2).added()).isEmpty();
        assertThat(seen.get(2).updated()).isEmpty();
        assertThat(events).as("no phantom changes from the stale listing").isEmpty();
        assertThat(store.statistics().changesDetected()).isEqualTo(3);
    }

    @Test
    void aReadOlderThanWhatWasReportedIsIgnored() throws Exception {
        driver.put("app/a", "1", 1);
        driver.stale = Map.copyOf(driver.current);
        try (AutoCloseable watch = store.watch("", Duration.ofMillis(10), events::add)) {
            driver.put("app/a", "2", 2);
            assertThat(events.poll(5, TimeUnit.SECONDS).updated().get("a").value()).isEqualTo("2");

            driver.put("app/a", "1", 1);   // a lagging read: version 1 again, older than what was reported
            Thread.sleep(200);
        }

        assertThat(events).isEmpty();
    }
}
