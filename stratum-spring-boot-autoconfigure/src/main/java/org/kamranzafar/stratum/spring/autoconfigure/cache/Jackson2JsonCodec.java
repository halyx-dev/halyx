package org.kamranzafar.stratum.spring.autoconfigure.cache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.kamranzafar.stratum.cache.Codec;
import org.springframework.cache.support.NullValue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * JSON cache values with Jackson 2 (Spring Boot 3). Each value is stored as
 * {@code {"@class": "<type>", "v": <json>}} and only types in the trusted packages are read back, so
 * there is no polymorphic deserialization of arbitrary classes. Generic element types (such as the
 * {@code Order} in {@code List<Order>}) are not recorded: cache concrete types, or use the jdk codec.
 */
public final class Jackson2JsonCodec implements Codec<Object> {

    private final ObjectMapper mapper;
    private final TrustedClasses trusted;

    public Jackson2JsonCodec(ObjectMapper mapper, List<String> trustedPackages) {
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
        try {
            return mapper.writeValueAsBytes(envelope);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Object decode(byte[] bytes) {
        try {
            JsonNode envelope = mapper.readTree(bytes);
            if (envelope.has("@null")) {
                return NullValue.INSTANCE;
            }
            return mapper.treeToValue(envelope.get("v"), trusted.load(envelope.get("@class").asText()));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read cached JSON value", e);
        }
    }
}
