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
package org.kamranzafar.cirrus.aws.dynamodb;

import org.junit.jupiter.api.Test;
import org.kamranzafar.cirrus.error.ThrottledException;
import org.kamranzafar.cirrus.nosql.Document;
import org.kamranzafar.cirrus.nosql.Key;
import org.kamranzafar.cirrus.nosql.TableConfig;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unprocessed batch entries fail fast with a retryable error: one request, no sleeping and resending. */
class DynamoBatchThrottlingTest {

    private final AtomicInteger calls = new AtomicInteger();

    /** Answers every batch by processing the first entry and leaving the rest unprocessed. */
    private final DynamoDbClient throttling = new DynamoDbClient() {
        @Override
        public BatchGetItemResponse batchGetItem(BatchGetItemRequest request) {
            calls.incrementAndGet();
            KeysAndAttributes asked = request.requestItems().get("items");
            Map<String, AttributeValue> first = new LinkedHashMap<>(asked.keys().getFirst());
            first.put("name", AttributeValue.fromS("first"));
            return BatchGetItemResponse.builder()
                    .responses(Map.of("items", List.of(first)))
                    .unprocessedKeys(Map.of("items", asked.toBuilder().keys(asked.keys().subList(1, asked.keys().size())).build()))
                    .build();
        }

        @Override
        public BatchWriteItemResponse batchWriteItem(BatchWriteItemRequest request) {
            calls.incrementAndGet();
            List<WriteRequest> asked = request.requestItems().get("items");
            return BatchWriteItemResponse.builder()
                    .unprocessedItems(Map.of("items", asked.subList(1, asked.size())))
                    .build();
        }

        @Override
        public String serviceName() {
            return "dynamodb";
        }

        @Override
        public void close() {
        }
    };

    private final DynamoDocumentStoreDriver driver = new DynamoDocumentStoreDriver(throttling, TableConfig.of("items"));

    @Test
    void unprocessedReadsThrowThrottledAfterOneRequest() {
        long start = System.nanoTime();
        assertThatThrownBy(() -> driver.getAll(List.of(Key.of("a"), Key.of("b"), Key.of("c"))))
                .isInstanceOfSatisfying(ThrottledException.class, e -> {
                    assertThat(e.isRetryable()).isTrue();
                    assertThat(e.getMessage()).contains("2 item(s) were not read").contains("b").contains("c");
                });
        assertThat(calls).hasValue(1);
        assertThat(System.nanoTime() - start).as("no backoff sleep").isLessThan(1_000_000_000L);
    }

    @Test
    void unprocessedWritesThrowThrottledAfterOneRequest() {
        Map<Key, Document> items = new LinkedHashMap<>();
        for (String id : List.of("a", "b", "c")) {
            items.put(Key.of(id), Document.of(Map.of("name", id)));
        }
        assertThatThrownBy(() -> driver.putAll(items))
                .isInstanceOfSatisfying(ThrottledException.class, e ->
                        assertThat(e.getMessage()).contains("2 item(s) were not written").contains("b").contains("c"));
        assertThat(calls).hasValue(1);
    }
}
