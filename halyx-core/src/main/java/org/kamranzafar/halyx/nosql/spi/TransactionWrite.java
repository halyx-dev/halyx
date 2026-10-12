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
package org.kamranzafar.halyx.nosql.spi;

import org.kamranzafar.halyx.nosql.Document;
import org.kamranzafar.halyx.nosql.DocumentUpdate;
import org.kamranzafar.halyx.nosql.Key;
import org.kamranzafar.halyx.nosql.Version;

import java.time.Instant;
import java.util.Optional;

/** A transaction operation as drivers see it, with conditions and expiry resolved. */
public sealed interface TransactionWrite {

    Key key();

    /** {@code expiresAt} is null for no expiry. */
    record Put(Key key, Document document, WriteCondition condition, Instant expiresAt) implements TransactionWrite {
    }

    /** The item must exist; {@code expiresAt} replaces its expiry when not null. */
    record Update(Key key, DocumentUpdate update, Optional<Version> ifVersion, Instant expiresAt) implements TransactionWrite {
    }

    /** Without {@code ifVersion}, deleting a missing item is not a failure. */
    record Delete(Key key, Optional<Version> ifVersion) implements TransactionWrite {
    }

    record Check(Key key, Version version) implements TransactionWrite {
    }
}
