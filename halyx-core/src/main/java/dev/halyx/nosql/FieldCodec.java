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
package dev.halyx.nosql;

import dev.halyx.error.DataIntegrityException;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * The plaintext of an encrypted field: a {@link Document} value in a type-preserving binary form, so a value reads
 * back as the type it was written as on every store (decimals stay exact even where the store keeps doubles).
 * Documented in {@code docs/api/nosql.md}; existing data depends on it, so it only ever gains new tags.
 *
 * <pre>
 * encoding = version (u8, 1) value
 * value    = tag (u8) body; integers are big-endian, lengths and counts u32
 *   0 null      (no body)
 *   1 string    length, UTF-8
 *   2 boolean   u8 (0 or 1)
 *   3 long      i64
 *   4 double    IEEE 754 binary64
 *   5 decimal   scale (i32), length, unscaled value (two's complement)
 *   6 binary    length, bytes
 *   7 list      count, values
 *   8 document  count, then per field: length, UTF-8 name, value
 * </pre>
 */
final class FieldCodec {

    static final int VERSION = 1;

    private static final int NULL = 0;
    private static final int STRING = 1;
    private static final int BOOLEAN = 2;
    private static final int LONG = 3;
    private static final int DOUBLE = 4;
    private static final int DECIMAL = 5;
    private static final int BINARY = 6;
    private static final int LIST = 7;
    private static final int DOCUMENT = 8;

    private FieldCodec() {
    }

    /** Encodes a value already normalized by {@link Document}. */
    static byte[] encode(Object value) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(VERSION);
            write(out, value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private static void write(DataOutputStream out, Object value) throws IOException {
        switch (value) {
            case null -> out.writeByte(NULL);
            case String s -> {
                out.writeByte(STRING);
                writeBytes(out, s.getBytes(StandardCharsets.UTF_8));
            }
            case Boolean b -> {
                out.writeByte(BOOLEAN);
                out.writeByte(b ? 1 : 0);
            }
            case Long l -> {
                out.writeByte(LONG);
                out.writeLong(l);
            }
            case Double d -> {
                out.writeByte(DOUBLE);
                out.writeDouble(d);
            }
            case BigDecimal bd -> {
                out.writeByte(DECIMAL);
                out.writeInt(bd.scale());
                writeBytes(out, bd.unscaledValue().toByteArray());
            }
            case byte[] b -> {
                out.writeByte(BINARY);
                writeBytes(out, b);
            }
            case List<?> list -> {
                out.writeByte(LIST);
                out.writeInt(list.size());
                for (Object item : list) {
                    write(out, item);
                }
            }
            case Document document -> {
                out.writeByte(DOCUMENT);
                out.writeInt(document.size());
                for (String name : document.fieldNames()) {
                    writeBytes(out, name.getBytes(StandardCharsets.UTF_8));
                    write(out, document.get(name));
                }
            }
            default -> throw new IllegalArgumentException("Unsupported field type " + value.getClass().getName());
        }
    }

    private static void writeBytes(DataOutputStream out, byte[] bytes) throws IOException {
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    /** Decodes {@link #encode}'s output; throws {@code DataIntegrityException} for anything else. */
    static Object decode(byte[] encoded) {
        ByteBuffer in = ByteBuffer.wrap(encoded);
        try {
            int version = in.get() & 0xff;
            if (version != VERSION) {
                throw new DataIntegrityException("Unknown encrypted field encoding version " + version);
            }
            Object value = read(in);
            if (in.hasRemaining()) {
                throw new DataIntegrityException("Trailing bytes after an encrypted field's value");
            }
            return value;
        } catch (BufferUnderflowException | IllegalArgumentException e) {
            throw new DataIntegrityException("An encrypted field's value is malformed", e, null, null);
        }
    }

    private static Object read(ByteBuffer in) {
        int tag = in.get() & 0xff;
        return switch (tag) {
            case NULL -> null;
            case STRING -> new String(readBytes(in), StandardCharsets.UTF_8);
            case BOOLEAN -> switch (in.get()) {
                case 0 -> Boolean.FALSE;
                case 1 -> Boolean.TRUE;
                default -> throw new DataIntegrityException("An encrypted boolean is neither 0 nor 1");
            };
            case LONG -> in.getLong();
            case DOUBLE -> in.getDouble();
            case DECIMAL -> {
                int scale = in.getInt();
                yield new BigDecimal(new BigInteger(readBytes(in)), scale);
            }
            case BINARY -> readBytes(in);
            case LIST -> {
                int count = count(in);
                List<Object> list = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    list.add(read(in));
                }
                yield Collections.unmodifiableList(list);
            }
            case DOCUMENT -> {
                int count = count(in);
                Document.Builder document = Document.builder();
                for (int i = 0; i < count; i++) {
                    document.put(new String(readBytes(in), StandardCharsets.UTF_8), read(in));
                }
                yield document.build();
            }
            default -> throw new DataIntegrityException("Unknown encrypted field type tag " + tag);
        };
    }

    private static byte[] readBytes(ByteBuffer in) {
        byte[] bytes = new byte[count(in)];
        in.get(bytes);
        return bytes;
    }

    /** A length or count, which can't exceed what's left to read. */
    private static int count(ByteBuffer in) {
        int count = in.getInt();
        if (count < 0 || count > in.remaining()) {
            throw new DataIntegrityException("An encrypted field's value is truncated");
        }
        return count;
    }

    /** The AAD that binds a sealed field to its place: table, item key and field name, each length-prefixed. */
    static byte[] aad(String table, Key key, String field) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.write("halyx-field/1".getBytes(StandardCharsets.US_ASCII));
            writeBytes(out, table.getBytes(StandardCharsets.UTF_8));
            writeBytes(out, key.partition().getBytes(StandardCharsets.UTF_8));
            Optional<String> sort = key.sort();
            out.writeByte(sort.isPresent() ? 1 : 0);
            if (sort.isPresent()) {
                writeBytes(out, sort.get().getBytes(StandardCharsets.UTF_8));
            }
            writeBytes(out, field.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }
}
