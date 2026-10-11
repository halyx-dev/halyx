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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.NotSerializableException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * Java serialization of cache values: works for any {@link java.io.Serializable} type, including
 * generic collections. With trusted packages configured, deserialization is limited to them (and
 * {@code java.*}) by an {@link ObjectInputFilter}.
 */
public final class JdkSerializationCodec implements Codec<Object> {

    private final TrustedClasses trusted;

    public JdkSerializationCodec(List<String> trustedPackages) {
        this.trusted = new TrustedClasses(trustedPackages);
    }

    @Override
    public byte[] encode(Object value) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
        } catch (NotSerializableException e) {
            throw new IllegalArgumentException("Cached value is not Serializable: " + e.getMessage()
                    + " (use cirrus.cache-manager.codec=json, or make it Serializable)", e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    @Override
    public Object decode(byte[] bytes) {
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            if (trusted.isRestricted()) {
                in.setObjectInputFilter(info -> {
                    Class<?> type = info.serialClass();
                    if (type == null || type.isPrimitive() || type.isArray()) {
                        return ObjectInputFilter.Status.UNDECIDED;
                    }
                    return trusted.allows(type.getName()) ? ObjectInputFilter.Status.ALLOWED : ObjectInputFilter.Status.REJECTED;
                });
            }
            return in.readObject();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not deserialize cached value", e);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Cached value type not found: " + e.getMessage(), e);
        }
    }
}
