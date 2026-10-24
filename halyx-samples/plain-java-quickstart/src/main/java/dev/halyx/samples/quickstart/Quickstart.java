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
package dev.halyx.samples.quickstart;

import static java.nio.charset.StandardCharsets.UTF_8;

import dev.halyx.CloudContext;
import dev.halyx.Halyx;
import dev.halyx.config.ProviderConfig;
import dev.halyx.messaging.SubscriptionConfig;
import dev.halyx.parameters.ParameterStoreConfig;
import dev.halyx.storage.BlobContent;
import dev.halyx.storage.BlobKey;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Duration;

/**
 * Halyx without Spring: one blob, one message and one parameter, on the in-memory provider by default.
 * The provider and the resource names come from system properties; see the README.
 */
public final class Quickstart {

    /** What {@link #run} wrote and read back. */
    record Result(String blob, String message, String parameter) {
    }

    public static void main(String[] args) {
        try (var halyx = Halyx.builder().register("quickstart", providerConfig()).build()) {
            Result result = run(halyx.provider("quickstart"));
            System.out.println("blob:      " + result.blob());
            System.out.println("message:   " + result.message());
            System.out.println("parameter: " + result.parameter());
        }
    }

    static Result run(CloudContext cloud) {
        // Services are cheap to ask for; the subscription is created before the publish so it sees the message.
        var blobs = cloud.blobStore(System.getProperty("quickstart.bucket", "quickstart-bucket"));
        String topic = System.getProperty("quickstart.topic", "orders");
        var billing = cloud.subscription(SubscriptionConfig
                .builder(System.getProperty("quickstart.subscription", "orders-billing"))
                .topic(topic)       // only the memory provider needs this: it binds subscriptions to topics itself
                .build());
        var orders = cloud.publisher(topic);

        var parameterConfig = ParameterStoreConfig.parameters().prefix("quickstart");
        String location = System.getProperty("quickstart.parameter-location");
        if (location != null) {
            parameterConfig.location(location);
        }
        var parameters = cloud.parameterStore(parameterConfig.build());

        // Object storage: write a blob, read it back
        var key = BlobKey.of("greetings/hello.txt");
        blobs.put(key, BlobContent.ofBytes("hello, blob".getBytes(UTF_8)));
        String blob;
        try (var stored = blobs.get(key)) {
            blob = new String(stored.content().readAllBytes(), UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        // Messaging: publish, receive, ack. On a real cloud the first receive can come back empty, so keep
        // receiving for up to 30 seconds.
        orders.publish("hello, message");
        String message = null;
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (message == null && System.nanoTime() < deadline) {
            for (var received : billing.receive(10, Duration.ofSeconds(5))) {
                message = received.bodyAsString();
                received.ack();
            }
        }
        if (message == null) {
            throw new IllegalStateException("no message received from " + billing + " within 30 seconds");
        }

        // Parameters: write one, read it back
        parameters.put("greeting", "hello, parameter");
        String parameter = parameters.require("greeting");

        return new Result(blob, message, parameter);
    }

    /** The provider type, region, endpoint and settings come from {@code quickstart.provider*} system properties. */
    static ProviderConfig providerConfig() {
        var config = ProviderConfig.of(System.getProperty("quickstart.provider", "memory"));
        String region = System.getProperty("quickstart.provider.region");
        if (region != null) {
            config = config.region(region);
        }
        String endpoint = System.getProperty("quickstart.provider.endpoint");
        if (endpoint != null) {
            config = config.endpoint(URI.create(endpoint));
        }
        String prefix = "quickstart.provider.property.";
        for (String name : System.getProperties().stringPropertyNames()) {
            if (name.startsWith(prefix)) {
                config = config.property(name.substring(prefix.length()), System.getProperty(name));
            }
        }
        return config;
    }

    private Quickstart() {
    }
}
