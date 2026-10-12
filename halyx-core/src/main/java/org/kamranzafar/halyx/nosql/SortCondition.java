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

import java.util.Objects;

/** A condition on the sort key within one partition. Comparisons are lexicographic on the string. */
public sealed interface SortCondition {

    static SortCondition equalTo(String value) {
        return new Between(value, value);
    }

    static SortCondition beginsWith(String prefix) {
        return new BeginsWith(prefix);
    }

    /** Inclusive at both ends. */
    static SortCondition between(String from, String to) {
        return new Between(from, to);
    }

    static SortCondition greaterThan(String value) {
        return new Compare(Operator.GT, value);
    }

    static SortCondition greaterThanOrEqual(String value) {
        return new Compare(Operator.GE, value);
    }

    static SortCondition lessThan(String value) {
        return new Compare(Operator.LT, value);
    }

    static SortCondition lessThanOrEqual(String value) {
        return new Compare(Operator.LE, value);
    }

    /** Whether {@code sortKey} satisfies the condition. */
    boolean test(String sortKey);

    enum Operator { GT, GE, LT, LE }

    record BeginsWith(String prefix) implements SortCondition {
        public BeginsWith {
            Objects.requireNonNull(prefix, "prefix");
            if (prefix.isEmpty()) {
                throw new IllegalArgumentException("prefix must not be empty");
            }
        }

        @Override
        public boolean test(String sortKey) {
            return sortKey.startsWith(prefix);
        }
    }

    record Between(String from, String to) implements SortCondition {
        public Between {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            if (from.compareTo(to) > 0) {
                throw new IllegalArgumentException("from must not be after to");
            }
        }

        @Override
        public boolean test(String sortKey) {
            return sortKey.compareTo(from) >= 0 && sortKey.compareTo(to) <= 0;
        }
    }

    record Compare(Operator operator, String value) implements SortCondition {
        public Compare {
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(value, "value");
        }

        @Override
        public boolean test(String sortKey) {
            int c = sortKey.compareTo(value);
            return switch (operator) {
                case GT -> c > 0;
                case GE -> c >= 0;
                case LT -> c < 0;
                case LE -> c <= 0;
            };
        }
    }
}
