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
package org.kamranzafar.halyx.spring.autoconfigure;

import org.kamranzafar.halyx.config.ProviderConfig;
import org.kamranzafar.halyx.error.ThrottledException;
import org.kamranzafar.halyx.memory.InMemoryProvider;
import org.kamranzafar.halyx.spi.CloudProvider;
import org.kamranzafar.halyx.spi.ProviderConnection;
import org.kamranzafar.halyx.storage.spi.BlobStoreDriver;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
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
}
