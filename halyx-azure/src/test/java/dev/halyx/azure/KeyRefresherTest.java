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
package dev.halyx.azure;

import com.azure.core.credential.AzureKeyCredential;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeyRefresherTest {

    @Test
    void aRotatedKeyUpdatesTheCredential() {
        AtomicReference<String> key = new AtomicReference<>("first");
        try (KeyRefresher keys = new KeyRefresher(Duration.ofHours(1))) {
            AzureKeyCredential credential = keys.credential("test key", key::get);
            assertThat(credential.getKey()).isEqualTo("first");

            key.set("rotated");
            keys.refresh();

            assertThat(credential.getKey()).isEqualTo("rotated");
        }
    }

    @Test
    void aFailedOrBlankRefreshKeepsTheCurrentKey() {
        AtomicReference<String> key = new AtomicReference<>("first");
        try (KeyRefresher keys = new KeyRefresher(Duration.ofHours(1))) {
            AzureKeyCredential credential = keys.credential("test key", () -> {
                if (key.get() == null) {
                    throw new IllegalStateException("store unavailable");
                }
                return key.get();
            });

            key.set(null);
            keys.refresh();
            assertThat(credential.getKey()).isEqualTo("first");
            key.set(" ");
            keys.refresh();
            assertThat(credential.getKey()).isEqualTo("first");
        }
    }

    @Test
    void theKeyIsReadWhenTheCredentialIsMade() {
        try (KeyRefresher keys = new KeyRefresher(Duration.ofHours(1))) {
            assertThatThrownBy(() -> keys.credential("test key", () -> {
                throw new IllegalStateException("no secret named test-key");
            })).hasMessageContaining("test-key");
        }
    }

    @Test
    void refreshesOnItsInterval() throws Exception {
        AtomicReference<String> key = new AtomicReference<>("first");
        try (KeyRefresher keys = new KeyRefresher(Duration.ofMillis(50))) {
            AzureKeyCredential credential = keys.credential("test key", key::get);
            key.set("rotated");

            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (!credential.getKey().equals("rotated") && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(credential.getKey()).isEqualTo("rotated");
        }
    }

    @Test
    void theIntervalMustBePositive() {
        assertThatThrownBy(() -> new KeyRefresher(Duration.ZERO)).hasMessageContaining("azure.key-refresh-interval");
    }
}
