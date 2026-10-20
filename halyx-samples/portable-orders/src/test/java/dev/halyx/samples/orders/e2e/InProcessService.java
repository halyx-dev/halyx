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
package dev.halyx.samples.orders.e2e;

import dev.halyx.samples.orders.PortableOrdersApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The service started in this JVM on a free port, as {@code java -jar} would start it, with profiles and properties. */
final class InProcessService implements AutoCloseable {

    private final ConfigurableApplicationContext context;
    private final OrdersClient client;

    InProcessService(List<String> profiles, Map<String, String> properties) {
        List<String> args = new ArrayList<>(List.of("--server.port=0",
                "--spring.profiles.active=" + String.join(",", profiles)));
        properties.forEach((key, value) -> args.add("--" + key + "=" + value));
        context = new SpringApplicationBuilder(PortableOrdersApplication.class).run(args.toArray(String[]::new));
        client = new OrdersClient(URI.create("http://localhost:"
                + context.getEnvironment().getRequiredProperty("local.server.port")));
    }

    OrdersClient client() {
        return client;
    }

    @Override
    public void close() {
        context.close();
    }
}
