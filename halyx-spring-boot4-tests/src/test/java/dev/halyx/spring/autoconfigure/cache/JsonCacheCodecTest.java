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
package dev.halyx.spring.autoconfigure.cache;

import dev.halyx.cache.Codec;
import dev.halyx.spring.autoconfigure.HalyxAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.Serializable;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** The JSON cache codec on Spring Boot 4 with Jackson 2 on the classpath too, as a cloud module brings it (#9). */
class JsonCacheCodecTest {

    public record Message(String id, Instant at) implements Serializable {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(HalyxAutoConfiguration.class, HalyxCacheManagerAutoConfiguration.class))
            .withPropertyValues("halyx.providers.local.type=memory",
                    "halyx.cache-manager.endpoint=redis://localhost:6379", "halyx.cache-manager.codec=json",
                    "halyx.cache-manager.trusted-packages=dev.halyx.spring");

    @Test
    void jacksonThreeIsPreferred() {
        runner.run(context -> assertThat(context.getBean(HalyxCacheManagerAutoConfiguration.CODEC_BEAN))
                .isInstanceOf(Jackson3JsonCodec.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void javaTimeValuesRoundTrip() {
        runner.run(context -> {
            Codec<Object> codec = context.getBean(HalyxCacheManagerAutoConfiguration.CODEC_BEAN, Codec.class);
            Message message = new Message("1", Instant.parse("2026-10-24T09:30:00.123456Z"));

            assertThat(codec.decode(codec.encode(message))).isEqualTo(message);
        });
    }
}
