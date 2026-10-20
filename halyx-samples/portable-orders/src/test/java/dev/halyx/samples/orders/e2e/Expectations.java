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
package dev.halyx.samples.orders.e2e;

import java.time.Duration;
import java.util.Set;

/**
 * What a deployment is expected to be, which the suite checks against what the service reports about itself.
 *
 * @param cloud      the {@code orders.cloud} of the active cloud profile
 * @param features   the optional features expected on, among {@code events}, {@code streams}, {@code cache} and {@code sql}
 * @param maxLines   {@code orders.max-lines}, as the settings store or the defaults give it
 * @param currency   {@code orders.currency}, likewise
 * @param signingKey the receipt signing key in the secrets store
 * @param patience   how long asynchronous steps (listeners, routers, streams) may take
 */
public record Expectations(String cloud, Set<String> features, int maxLines, String currency, String signingKey,
                           Duration patience) {

    public boolean has(String feature) {
        return features.contains(feature);
    }
}
