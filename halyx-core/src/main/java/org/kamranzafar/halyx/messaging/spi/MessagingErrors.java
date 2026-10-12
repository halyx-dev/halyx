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
package org.kamranzafar.halyx.messaging.spi;

import org.kamranzafar.halyx.error.CloudException;
import org.kamranzafar.halyx.error.UnknownCloudException;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/** Shared plumbing for messaging drivers: error translation and native client lookup. */
public final class MessagingErrors {

    private MessagingErrors() {
    }

    public static CloudException translate(Throwable error, String provider,
                                           Function<Throwable, Optional<CloudException>> nativeTranslation) {
        if (error instanceof CloudException portable) {
            return portable;
        }
        return nativeTranslation.apply(error).orElseGet(() -> new UnknownCloudException(
                "Unexpected error from provider '" + provider + "': " + error.getMessage(), error, provider, null));
    }

    public static <T> T unwrap(Class<T> nativeType, String provider, List<Object> clients) {
        for (Object client : clients) {
            if (nativeType.isInstance(client)) {
                return nativeType.cast(client);
            }
        }
        throw new IllegalArgumentException("Provider '" + provider + "' has no native client of type " + nativeType.getName());
    }
}
