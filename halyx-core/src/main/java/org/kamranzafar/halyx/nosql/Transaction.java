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
package org.kamranzafar.halyx.nosql;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Writes applied atomically to items of one partition: all of them happen, or none do. Any failed condition
 * (a version that moved, an item that exists for {@code ifNotExists} or is missing for an update or check)
 * cancels the whole transaction with {@code PreconditionFailedException}. One partition and at most
 * {@value #MAX_OPERATIONS} operations keep it portable: Cosmos DB transactions span one partition, and
 * DynamoDB's and Cosmos DB's hold at most 100 items. Each item may appear once.
 */
public final class Transaction {

    public static final int MAX_OPERATIONS = 100;

    /** One operation of a transaction. */
    public sealed interface Operation {
        Key key();
    }

    public record Put(Key key, Document document, WriteOptions options) implements Operation {
        public Put {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(document, "document");
            Objects.requireNonNull(options, "options");
        }
    }

    public record Update(Key key, DocumentUpdate update, WriteOptions options) implements Operation {
        public Update {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(update, "update");
            Objects.requireNonNull(options, "options");
            if (options.notExists()) {
                throw new IllegalArgumentException("update needs an existing item; use put with ifNotExists");
            }
        }
    }

    public record Delete(Key key, Optional<Version> ifVersion) implements Operation {
        public Delete {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(ifVersion, "ifVersion");
        }
    }

    /** Writes nothing: the item must exist at {@code version} for the transaction to go ahead. */
    public record Check(Key key, Version version) implements Operation {
        public Check {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(version, "version");
        }
    }

    private final List<Operation> operations;

    private Transaction(List<Operation> operations) {
        this.operations = List.copyOf(operations);
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<Operation> operations() {
        return operations;
    }

    /** The partition every operation is in. */
    public String partition() {
        return operations.getFirst().key().partition();
    }

    public static final class Builder {
        private final List<Operation> operations = new ArrayList<>();

        private Builder() {
        }

        public Builder put(Key key, Document document) {
            return add(new Put(key, document, WriteOptions.defaults()));
        }

        public Builder put(Key key, Document document, WriteOptions options) {
            return add(new Put(key, document, options));
        }

        public Builder update(Key key, DocumentUpdate update) {
            return add(new Update(key, update, WriteOptions.defaults()));
        }

        public Builder update(Key key, DocumentUpdate update, WriteOptions options) {
            return add(new Update(key, update, options));
        }

        public Builder delete(Key key) {
            return add(new Delete(key, Optional.empty()));
        }

        public Builder delete(Key key, Version ifVersion) {
            return add(new Delete(key, Optional.of(ifVersion)));
        }

        public Builder check(Key key, Version version) {
            return add(new Check(key, version));
        }

        private Builder add(Operation operation) {
            operations.add(operation);
            return this;
        }

        public Transaction build() {
            if (operations.isEmpty()) {
                throw new IllegalStateException("A transaction needs at least one operation");
            }
            if (operations.size() > MAX_OPERATIONS) {
                throw new IllegalArgumentException("A transaction holds at most " + MAX_OPERATIONS + " operations");
            }
            String partition = operations.getFirst().key().partition();
            Set<Key> seen = new HashSet<>();
            for (Operation operation : operations) {
                if (!operation.key().partition().equals(partition)) {
                    throw new IllegalArgumentException("Every operation of a transaction must be in one partition: "
                            + partition + " and " + operation.key().partition());
                }
                if (!seen.add(operation.key())) {
                    throw new IllegalArgumentException("Item " + operation.key() + " appears twice in the transaction");
                }
            }
            return new Transaction(operations);
        }
    }
}
