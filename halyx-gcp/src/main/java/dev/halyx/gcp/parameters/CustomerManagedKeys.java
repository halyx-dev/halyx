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
package dev.halyx.gcp.parameters;

import dev.halyx.error.InvalidArgumentException;
import dev.halyx.gcp.GcpProvider;
import dev.halyx.keys.spi.ServerSideKey;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Checks a Cloud KMS key for a parameter service, which can only use a key in the location it stores values in. */
final class CustomerManagedKeys {

    private static final Pattern KEY = Pattern.compile("projects/[^/]+/locations/([^/]+)/keyRings/[^/]+/cryptoKeys/[^/]+");

    private CustomerManagedKeys() {
    }

    /** The key's crypto key name, refused unless it is in {@code location}. */
    static String inLocation(ServerSideKey key, String location, String service) {
        Matcher matcher = KEY.matcher(key.nativeId());
        if (!matcher.matches()) {
            throw new InvalidArgumentException("'" + key.nativeId() + "' is not a Cloud KMS crypto key name", null,
                    GcpProvider.ID, null);
        }
        if (!matcher.group(1).equals(location)) {
            throw new InvalidArgumentException(service + " stores these values in '" + location
                    + "', so it can only encrypt them with a key in a key ring there; '" + key.nativeId() + "' is in '"
                    + matcher.group(1) + "'", null, GcpProvider.ID, null);
        }
        return key.nativeId();
    }
}
