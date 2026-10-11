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
package org.kamranzafar.cirrus.aws.messaging;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SNS and SQS carry text only (and SQS rejects some control characters), so bodies that aren't plain
 * UTF-8 text are base64-encoded and marked with the {@value #ENCODING} attribute, which the subscription
 * reverses. Text bodies travel as they are, readable in the AWS console.
 */
final class AwsBodies {

    static final String ENCODING = "cirrus-encoding";
    static final String BASE64 = "base64";

    private AwsBodies() {
    }

    record Encoded(String text, boolean base64) {
    }

    static Encoded encode(byte[] body) {
        String text = sendableText(body);
        return text != null ? new Encoded(text, false) : new Encoded(Base64.getEncoder().encodeToString(body), true);
    }

    static byte[] decode(String text, Map<String, String> attributes) {
        return BASE64.equals(attributes.get(ENCODING))
                ? Base64.getDecoder().decode(text)
                : text.getBytes(StandardCharsets.UTF_8);
    }

    static Map<String, String> withoutEncoding(Map<String, String> attributes) {
        if (!attributes.containsKey(ENCODING)) {
            return attributes;
        }
        Map<String, String> copy = new LinkedHashMap<>(attributes);
        copy.remove(ENCODING);
        return copy;
    }

    /** The body as text if it is valid UTF-8 using only characters SQS accepts, else null. */
    private static String sendableText(byte[] body) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            boolean allowed = cp == 0x9 || cp == 0xA || cp == 0xD || (cp >= 0x20 && cp <= 0xD7FF)
                    || (cp >= 0xE000 && cp <= 0xFFFD) || (cp >= 0x10000 && cp <= 0x10FFFF);
            if (!allowed) {
                return null;
            }
            i += Character.charCount(cp);
        }
        return text;
    }
}
