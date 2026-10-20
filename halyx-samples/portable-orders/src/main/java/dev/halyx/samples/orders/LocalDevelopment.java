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
package dev.halyx.samples.orders;

import dev.halyx.parameters.ParameterStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The {@code local} profile keeps everything in memory, so nothing has stored a signing key; this stores a development
 * one at startup. On a cloud, the key is a secret created with the infrastructure.
 */
@Component
@Profile("local")
class LocalDevelopment implements ApplicationRunner {

    private final ParameterStore secrets;
    private final String signingKey;

    LocalDevelopment(@Qualifier("secrets") ParameterStore secrets,
                     @Value("${orders.local.signing-key:local-development-key}") String signingKey) {
        this.secrets = secrets;
        this.signingKey = signingKey;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (secrets.get(ReceiptService.SIGNING_KEY).isEmpty()) {
            secrets.put(ReceiptService.SIGNING_KEY, signingKey);
        }
    }
}
