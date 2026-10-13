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
package dev.halyx.spi;

import dev.halyx.config.ProviderConfig;

/**
 * Plugin entry point for a cloud implementation, discovered with {@link java.util.ServiceLoader}.
 * Provider modules register an implementation in
 * {@code META-INF/services/dev.halyx.spi.CloudProvider}, so adding a provider is just
 * adding its jar to the classpath.
 */
public interface CloudProvider {

    /** Stable id used in {@link ProviderConfig#of(String)}, such as {@code "aws"}. */
    String id();

    /** Opens a connection. Implementations should create native clients lazily. */
    ProviderConnection connect(ProviderConfig config);
}
