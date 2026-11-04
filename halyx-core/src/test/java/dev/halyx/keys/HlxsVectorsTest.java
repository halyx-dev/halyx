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
package dev.halyx.keys;

import dev.halyx.error.DataIntegrityException;
import dev.halyx.error.InvalidArgumentException;
import dev.halyx.json.Json;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The published HLXS v1 test vectors ({@code hlxs/hlxs-v1-vectors.json}), which every implementation must pass:
 * readers open or refuse each one as it says, and the writer reproduces every vector that gives its inputs byte for
 * byte. The Python decoder in {@code tools/hlxs-python} runs the same file.
 */
class HlxsVectorsTest {

    private static final HexFormat HEX = HexFormat.of();

    @TestFactory
    Stream<DynamicTest> everyVector() throws IOException {
        Map<String, Object> file = vectors();
        assertThat(file.get("format")).isEqualTo("HLXS");
        assertThat(((Number) file.get("formatVersion")).intValue()).isEqualTo(Hlxs.FORMAT_VERSION);
        List<Map<String, Object>> vectors = list(file.get("vectors"));
        assertThat(vectors).hasSizeGreaterThanOrEqualTo(30);
        return vectors.stream().map(v -> DynamicTest.dynamicTest(name(v), () -> check(v)));
    }

    private static void check(Map<String, Object> vector) {
        byte[] dek = hex(vector, "dataKey");
        byte[] aad = hex(vector, "aad");
        byte[] sealed = hex(vector, "sealed");
        switch ((String) vector.get("expect")) {
            case "ok" -> {
                SealedData data = SealedData.parse(sealed);
                assertThat(Hlxs.open(dek, data.header(), data.bytesUnsafe(), aad)).isEqualTo(hex(vector, "plaintext"));
                if (vector.containsKey("inputs")) {
                    assertThat(HEX.formatHex(reseal(dek, aad, hex(vector, "plaintext"), map(vector.get("inputs")))))
                            .as("the writer reproduces the vector").isEqualTo(vector.get("sealed"));
                }
            }
            case "invalid" -> assertThatThrownBy(() -> open(dek, aad, sealed))
                    .isInstanceOf(InvalidArgumentException.class);
            case "integrity" -> assertThatThrownBy(() -> open(dek, aad, sealed))
                    .isInstanceOf(DataIntegrityException.class);
            default -> throw new AssertionError("Unknown expectation " + vector.get("expect"));
        }
    }

    private static void open(byte[] dek, byte[] aad, byte[] sealed) {
        SealedData data = SealedData.parse(sealed);
        Hlxs.open(dek, data.header(), data.bytesUnsafe(), aad);
    }

    private static byte[] reseal(byte[] dek, byte[] aad, byte[] plaintext, Map<String, Object> inputs) {
        List<WrappedKey> keys = new ArrayList<>();
        for (Map<String, Object> key : list(inputs.get("keys"))) {
            keys.add(new WrappedKey((String) key.get("keyId"), (String) key.get("version"),
                    ((Number) key.get("wrapAlgorithm")).intValue(), hex(key, "wrapped")));
        }
        return Hlxs.seal(dek, keys, plaintext, aad, ((Number) inputs.get("segmentSize")).intValue(), hex(inputs, "salt"),
                hex(inputs, "noncePrefix"));
    }

    private static Map<String, Object> vectors() throws IOException {
        try (InputStream in = HlxsVectorsTest.class.getResourceAsStream("/hlxs/hlxs-v1-vectors.json")) {
            assertThat(in).as("hlxs-v1-vectors.json on the test classpath").isNotNull();
            return map(Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
        }
    }

    private static String name(Map<String, Object> vector) {
        return vector.get("name") + " (" + vector.get("expect") + ")";
    }

    private static byte[] hex(Map<String, Object> map, String field) {
        return HEX.parseHex((String) map.get(field));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value) {
        return (List<Map<String, Object>>) value;
    }
}
