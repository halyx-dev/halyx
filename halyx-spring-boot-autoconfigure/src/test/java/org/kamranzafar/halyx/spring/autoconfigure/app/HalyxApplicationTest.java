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
package org.kamranzafar.halyx.spring.autoconfigure.app;

import org.junit.jupiter.api.Test;
import org.kamranzafar.halyx.Halyx;
import org.kamranzafar.halyx.cache.Cache;
import org.kamranzafar.halyx.storage.BlobStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Service;

import static org.assertj.core.api.Assertions.assertThat;

/** A real Boot application: auto-configuration is found through AutoConfiguration.imports. */
class HalyxApplicationTest {

    @Test
    void autoConfiguresAndInjectsBlobStoresByQualifier() {
        try (var context = new SpringApplicationBuilder(App.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "halyx.providers.local.type=memory",
                        "halyx.blob-stores.invoices.bucket=acme-invoices",
                        "halyx.blob-stores.avatars.bucket=acme-avatars",
                        "halyx.caches.sessions.endpoint=redis://localhost:6379",
                        // JDBC is on the test classpath for the database tests; this app has no database.
                        "spring.autoconfigure.exclude=" + dataSourceAutoConfiguration())
                .run()) {
            InvoiceService service = context.getBean(InvoiceService.class);

            assertThat(service.invoices.bucket()).isEqualTo("acme-invoices");
            service.sessions.put("s1", "alice");
            assertThat(service.sessions.get("s1")).hasValue("alice");
            assertThat(context.getBean(Halyx.class).names()).containsExactly("local");
        }
    }

    @SpringBootApplication
    @Import(InvoiceService.class)
    static class App {
    }

    @Service
    static class InvoiceService {
        final BlobStore invoices;
        final Cache<String> sessions;

        InvoiceService(@Qualifier("invoices") BlobStore invoices, Cache<String> sessions) {
            this.invoices = invoices;
            this.sessions = sessions;
        }
    }

    private static String dataSourceAutoConfiguration() {
        String boot3 = "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration";
        try {
            Class.forName(boot3);
            return boot3;
        } catch (ClassNotFoundException e) {
            return "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration";
        }
    }
}
