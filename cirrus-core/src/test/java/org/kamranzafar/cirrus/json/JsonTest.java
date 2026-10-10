package org.kamranzafar.cirrus.json;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonTest {

    @Test
    void parsesVaultResponses() {
        Object parsed = Json.parse("""
                {"data": {"data": {"value": "line1\\nline2 \\"q\\" \\u00e4"}, "metadata": {"version": 3, "created_time": "2026-10-10T12:00:00Z", "deletion_time": "", "destroyed": false}},
                 "keys": ["a", "b/"], "ratio": 1.5e2, "none": null}""");

        assertThat(Json.at(parsed, "data", "data", "value")).isEqualTo("line1\nline2 \"q\" ä");
        assertThat(Json.at(parsed, "data", "metadata", "version")).isEqualTo(3L);
        assertThat(Json.at(parsed, "data", "metadata", "destroyed")).isEqualTo(false);
        assertThat(Json.at(parsed, "keys")).isEqualTo(List.of("a", "b/"));
        assertThat(Json.at(parsed, "ratio")).isEqualTo(150.0);
        assertThat(Json.at(parsed, "none")).isNull();
        assertThat(Json.at(parsed, "missing", "deeper")).isNull();
    }

    @Test
    void writesAndRoundTrips() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("text", "tab\there \"quoted\" \\ \u0001");
        value.put("list", List.of(1L, true, "x"));
        value.put("nested", Map.of("k", "v"));

        String written = Json.write(value);

        assertThat(written).startsWith("{\"text\":\"tab\\there \\\"quoted\\\" \\\\ \\u0001\"");
        assertThat(Json.parse(written)).isEqualTo(value);
    }

    @Test
    void flattensNestedObjectsLikeSpringProperties() {
        var object = Json.parseObject("{\"username\":\"app\",\"port\":5432,\"db\":{\"host\":\"h\",\"ssl\":true},\"hosts\":[\"a\",\"b\"],\"x\":null}").orElseThrow();

        assertThat(Json.flatten(object)).containsExactly(
                Map.entry("username", "app"), Map.entry("port", "5432"), Map.entry("db.host", "h"),
                Map.entry("db.ssl", "true"), Map.entry("hosts[0]", "a"), Map.entry("hosts[1]", "b"));
    }

    @Test
    void parseObjectIgnoresNonObjects() {
        assertThat(Json.parseObject("plain text")).isEmpty();
        assertThat(Json.parseObject("[1,2]")).isEmpty();
        assertThat(Json.parseObject("{broken")).isEmpty();
    }

    @Test
    void rejectsMalformedInput() {
        assertThatThrownBy(() -> Json.parse("{\"a\": }")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Json.parse("{\"a\": 1} extra")).isInstanceOf(IllegalArgumentException.class);
    }
}
