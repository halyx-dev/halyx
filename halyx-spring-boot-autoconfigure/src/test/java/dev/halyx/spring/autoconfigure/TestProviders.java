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
package dev.halyx.spring.autoconfigure;

import dev.halyx.cache.CacheConfig;
import dev.halyx.cache.spi.CacheDriver;
import dev.halyx.config.ProviderConfig;
import dev.halyx.error.ThrottledException;
import dev.halyx.error.UnavailableException;
import dev.halyx.parameters.ParameterStoreConfig;
import dev.halyx.parameters.spi.ParameterDriver;
import dev.halyx.memory.InMemoryProvider;
import dev.halyx.spi.CloudProvider;
import dev.halyx.spi.ProviderConnection;
import dev.halyx.storage.spi.BlobStoreDriver;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Provider plugins for tests, shared by the Boot 3 and Boot 4 test suites. */
public final class TestProviders {

    private TestProviders() {
    }

    /**
     * The in-memory provider under the id {@code flaky}, whose {@code head} and {@code list} fail with
     * a retryable error for the first {@code failures} calls.
     */
    public static final class Flaky implements CloudProvider {

        public final AtomicInteger calls = new AtomicInteger();
        private final int failures;

        public Flaky(int failures) {
            this.failures = failures;
        }

        @Override
        public String id() {
            return "flaky";
        }

        @Override
        public ProviderConnection connect(ProviderConfig config) {
            ProviderConnection memory = new InMemoryProvider().connect(config);
            return new ProviderConnection() {
                @Override
                public BlobStoreDriver blobStore(String bucket) {
                    BlobStoreDriver delegate = memory.blobStore(bucket);
                    return (BlobStoreDriver) Proxy.newProxyInstance(BlobStoreDriver.class.getClassLoader(),
                            new Class<?>[] {BlobStoreDriver.class}, (proxy, method, args) -> {
                                String name = method.getName();
                                if ((name.equals("head") || name.equals("list")) && calls.incrementAndGet() <= failures) {
                                    throw new ThrottledException("slow down");
                                }
                                try {
                                    return method.invoke(delegate, args);
                                } catch (InvocationTargetException e) {
                                    throw e.getCause();
                                }
                            });
                }

                @Override
                public void close() {
                    memory.close();
                }
            };
        }
    }

    /**
     * The in-memory provider under the id {@code down}, whose cache and parameter store servers fail with a retryable
     * error while {@link #down} is set; {@link #parameterReads} counts the reads that reach the parameter backend.
     */
    public static final class Down implements CloudProvider {

        public final AtomicBoolean down = new AtomicBoolean();
        public final AtomicInteger parameterReads = new AtomicInteger();

        @Override
        public String id() {
            return "down";
        }

        @Override
        public ProviderConnection connect(ProviderConfig config) {
            ProviderConnection memory = new InMemoryProvider().connect(config);
            return new ProviderConnection() {
                @Override
                public CacheDriver cache(CacheConfig cache) {
                    return failing(CacheDriver.class, memory.cache(cache), Set.of());
                }

                @Override
                public ParameterDriver parameterStore(ParameterStoreConfig store) {
                    return failing(ParameterDriver.class, memory.parameterStore(store), Set.of("get", "list"));
                }

                @Override
                public void close() {
                    memory.close();
                }
            };
        }

        /** {@code delegate}, failing while down; calls to {@code reads} are counted as parameter reads. */
        private <T> T failing(Class<T> type, T delegate, Set<String> reads) {
            return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
                String name = method.getName();
                boolean local = name.equals("translate") || name.equals("unwrap") || name.equals("capabilities")
                        || name.equals("close");
                if (!local) {
                    if (reads.contains(name)) {
                        parameterReads.incrementAndGet();
                    }
                    if (down.get()) {
                        throw new UnavailableException("connection refused");
                    }
                }
                try {
                    return method.invoke(delegate, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            }));
        }
    }
}
