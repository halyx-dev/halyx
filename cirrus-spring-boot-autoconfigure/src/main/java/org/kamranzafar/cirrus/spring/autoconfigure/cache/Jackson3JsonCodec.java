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
package org.kamranzafar.cirrus.spring.autoconfigure.cache;

import org.kamranzafar.cirrus.cache.Codec;
import org.springframework.cache.support.NullValue;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * JSON cache values with Jackson 3 (Spring Boot 4); the same format as {@link Jackson2JsonCodec}, so
 * applications can move between Boot versions without invalidating their caches.
 */
public final class Jackson3JsonCodec implements Codec<Object> {

    private final ObjectMapper mapper;
    private final TrustedClasses trusted;

    public Jackson3JsonCodec(ObjectMapper mapper, List<String> trustedPackages) {
        this.mapper = mapper;
        this.trusted = new TrustedClasses(trustedPackages);
    }

    @Override
    public byte[] encode(Object value) {
        ObjectNode envelope = mapper.createObjectNode();
        if (value instanceof NullValue) {
            envelope.put("@null", true);
        } else {
            envelope.put("@class", value.getClass().getName());
            envelope.set("v", mapper.valueToTree(value));
        }
        return mapper.writeValueAsBytes(envelope);
    }

    @Override
    public Object decode(byte[] bytes) {
        JsonNode envelope = mapper.readTree(bytes);
        if (envelope.has("@null")) {
            return NullValue.INSTANCE;
        }
        return mapper.treeToValue(envelope.get("v"), trusted.load(envelope.get("@class").asString()));
    }
}
