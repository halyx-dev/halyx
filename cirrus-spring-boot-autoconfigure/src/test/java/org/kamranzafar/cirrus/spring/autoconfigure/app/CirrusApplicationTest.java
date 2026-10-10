package org.kamranzafar.cirrus.spring.autoconfigure.app;

import org.junit.jupiter.api.Test;
import org.kamranzafar.cirrus.Cirrus;
import org.kamranzafar.cirrus.cache.Cache;
import org.kamranzafar.cirrus.storage.BlobStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Service;

import static org.assertj.core.api.Assertions.assertThat;

/** A real Boot application: auto-configuration is found through AutoConfiguration.imports. */
class CirrusApplicationTest {

    @Test
    void autoConfiguresAndInjectsBlobStoresByQualifier() {
        try (var context = new SpringApplicationBuilder(App.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "cirrus.providers.local.type=memory",
                        "cirrus.blob-stores.invoices.bucket=acme-invoices",
                        "cirrus.blob-stores.avatars.bucket=acme-avatars",
                        "cirrus.caches.sessions.endpoint=redis://localhost:6379",
                        // JDBC is on the test classpath for the database tests; this app has no database.
                        "spring.autoconfigure.exclude=" + dataSourceAutoConfiguration())
                .run()) {
            InvoiceService service = context.getBean(InvoiceService.class);

            assertThat(service.invoices.bucket()).isEqualTo("acme-invoices");
            service.sessions.put("s1", "alice");
            assertThat(service.sessions.get("s1")).hasValue("alice");
            assertThat(context.getBean(Cirrus.class).names()).containsExactly("local");
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
