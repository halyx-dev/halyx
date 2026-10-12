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
package org.kamranzafar.halyx.kafka;

import java.time.Instant;
import java.util.Objects;

/**
 * An {@code OAUTHBEARER} token for logging in to Kafka.
 *
 * @param token     the token sent to the broker
 * @param principal who the token is for, as the client reports it
 * @param issuedAt  when the token was issued
 * @param expiresAt when the broker stops accepting it for new logins
 */
public record KafkaLogin(String token, String principal, Instant issuedAt, Instant expiresAt) {

    public KafkaLogin {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    @Override
    public String toString() {
        return "KafkaLogin[principal=" + principal + ", issuedAt=" + issuedAt + ", expiresAt=" + expiresAt + "]";
    }
}
