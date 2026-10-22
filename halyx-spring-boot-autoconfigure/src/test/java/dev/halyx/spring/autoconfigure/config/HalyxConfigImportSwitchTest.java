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
package dev.halyx.spring.autoconfigure.config;

import dev.halyx.spring.autoconfigure.HalyxAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code halyx.config-import.enabled=false}: an import of a store nobody can reach loads nothing and never connects,
 * so a test can resolve a cloud profile as written. Without it, the same import fails startup.
 */
class HalyxConfigImportSwitchTest {

    @TempDir
    static Path dir;

    @SpringBootConfiguration
    @ImportAutoConfiguration(HalyxAutoConfiguration.class)
    static class App {
    }

    /** A Vault on a port nothing listens on, tried once. */
    private static ConfigurableApplicationContext start(String... args) throws Exception {
        Path file = Files.writeString(dir.resolve("application.properties"), """
                spring.config.import=halyx:settings
                halyx.providers.vault.type=vault
                halyx.providers.vault.endpoint=http://127.0.0.1:1
                halyx.providers.vault.credentials.type=static
                halyx.providers.vault.credentials.id=token
                halyx.providers.vault.credentials.secret=unused
                halyx.parameter-stores.settings.prefix=app
                halyx.parameter-stores.settings.startup-attempts=1
                app.greeting=from-the-file
                #---
                spring.config.activate.on-profile=cloud
                spring.config.import=halyx:settings/profile-only
                """);
        SpringApplication application = new SpringApplication(App.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        String[] all = new String[args.length + 1];
        all[0] = "--spring.config.location=file:" + file;
        System.arraycopy(args, 0, all, 1, args.length);
        return application.run(all);
    }

    @Test
    void importsLoadNothingWhenSwitchedOff() throws Exception {
        try (var context = start("--halyx.config-import.enabled=false", "--spring.profiles.active=cloud")) {
            assertThat(context.getEnvironment().getProperty("app.greeting")).isEqualTo("from-the-file");
            assertThat(context.getEnvironment().getProperty("halyx.parameter-stores.settings.prefix")).isEqualTo("app");
        }
    }

    @Test
    void importsConnectByDefault() {
        assertThatThrownBy(() -> start().close()).isNotNull();
    }
}
