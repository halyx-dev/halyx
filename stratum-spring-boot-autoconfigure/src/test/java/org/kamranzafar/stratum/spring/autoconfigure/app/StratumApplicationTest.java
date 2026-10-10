package org.kamranzafar.stratum.spring.autoconfigure.app;

import org.junit.jupiter.api.Test;
import org.kamranzafar.stratum.Stratum;
import org.kamranzafar.stratum.storage.BlobStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Service;

import static org.assertj.core.api.Assertions.assertThat;

/** A real Boot application: auto-configuration is found through AutoConfiguration.imports. */
class StratumApplicationTest {

    @Test
    void autoConfiguresAndInjectsBlobStoresByQualifier() {
        try (var context = new SpringApplicationBuilder(App.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "stratum.providers.local.type=memory",
                        "stratum.blob-stores.invoices.bucket=acme-invoices",
                        "stratum.blob-stores.avatars.bucket=acme-avatars")
                .run()) {
            InvoiceService service = context.getBean(InvoiceService.class);

            assertThat(service.invoices.bucket()).isEqualTo("acme-invoices");
            assertThat(context.getBean(Stratum.class).names()).containsExactly("local");
        }
    }

    @SpringBootApplication
    @Import(InvoiceService.class)
    static class App {
    }

    @Service
    static class InvoiceService {
        final BlobStore invoices;

        InvoiceService(@Qualifier("invoices") BlobStore invoices) {
            this.invoices = invoices;
        }
    }
}
