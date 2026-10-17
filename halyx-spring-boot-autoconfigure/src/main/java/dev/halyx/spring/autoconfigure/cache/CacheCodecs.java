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
import org.springframework.beans.factory.BeanFactory;
import org.springframework.util.ClassUtils;

import java.util.List;

/**
 * The object codecs that {@code halyx.caches.<name>.codec} and {@code halyx.cache-manager.codec} name: {@code jdk}
 * serialization or {@code json}, each reading back only {@code java.*} types and those in the trusted packages.
 */
public final class CacheCodecs {

    private static final ClassLoader LOADER = CacheCodecs.class.getClassLoader();

    private CacheCodecs() {
    }

    /** {@code jdk} or {@code json}; {@code property} names the setting, for error messages. */
    public static Codec<Object> of(String name, List<String> trustedPackages, BeanFactory beans, String property) {
        return switch (name) {
            case "jdk" -> new JdkSerializationCodec(trustedPackages);
            case "json" -> json(trustedPackages, beans, property);
            default -> throw new IllegalStateException(property + ".codec must be jdk or json, not '" + name + "'");
        };
    }

    /** JSON with the application's mapper if it has one: Jackson 3 on Boot 4, Jackson 2 on Boot 3. */
    private static Codec<Object> json(List<String> trustedPackages, BeanFactory beans, String property) {
        if (trustedPackages.isEmpty()) {
            throw new IllegalStateException(property + ".trusted-packages is required with codec=json: "
                    + "list the packages of your cached types, such as com.example.orders");
        }
        if (ClassUtils.isPresent("tools.jackson.databind.ObjectMapper", LOADER)) {
            return Jackson3.codec(trustedPackages, beans);
        }
        if (ClassUtils.isPresent("com.fasterxml.jackson.databind.ObjectMapper", LOADER)) {
            return Jackson2.codec(trustedPackages, beans);
        }
        throw new IllegalStateException(property + ".codec=json needs Jackson (3 on Boot 4, 2 on Boot 3) on the classpath");
    }

    /** Loaded only when Jackson 3 is present. */
    private static final class Jackson3 {
        static Codec<Object> codec(List<String> trustedPackages, BeanFactory beans) {
            return new Jackson3JsonCodec(beans.getBeanProvider(tools.jackson.databind.ObjectMapper.class)
                    .getIfAvailable(tools.jackson.databind.json.JsonMapper::new), trustedPackages);
        }
    }

    /** Loaded only when Jackson 2 is present. */
    private static final class Jackson2 {
        static Codec<Object> codec(List<String> trustedPackages, BeanFactory beans) {
            return new Jackson2JsonCodec(beans.getBeanProvider(com.fasterxml.jackson.databind.ObjectMapper.class)
                    .getIfAvailable(com.fasterxml.jackson.databind.ObjectMapper::new), trustedPackages);
        }
    }
}
