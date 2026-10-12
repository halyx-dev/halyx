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
package org.kamranzafar.halyx.storage;

import org.junit.jupiter.api.Test;
import org.kamranzafar.halyx.error.UnavailableException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BulkOperationsTest {

    @Test
    void nextPageIsFetchedWhileTheCurrentOneIsConsumed() throws InterruptedException {
        var secondRequested = new CountDownLatch(1);
        var iterator = new PagingIterator(ListOptions.prefix(""), options -> {
            int page = options.pageToken().map(Integer::parseInt).orElse(0);
            if (page == 1) {
                secondRequested.countDown();
            }
            return page(page, page < 2 ? Optional.of(String.valueOf(page + 1)) : Optional.empty());
        });

        assertThat(iterator.next().key().value()).isEqualTo("p0-0");
        // Page 1 is requested before page 0 has been fully consumed.
        assertThat(secondRequested.await(2, TimeUnit.SECONDS)).isTrue();

        List<String> rest = new ArrayList<>();
        iterator.forEachRemaining(s -> rest.add(s.key().value()));
        assertThat(rest).containsExactly("p0-1", "p1-0", "p1-1", "p2-0", "p2-1");
    }

    @Test
    void prefetchFailureSurfacesWhenThePageIsReached() {
        var iterator = new PagingIterator(ListOptions.prefix(""), options -> {
            if (options.pageToken().isPresent()) {
                throw new UnavailableException("down");
            }
            return page(0, Optional.of("1"));
        });

        assertThat(iterator.next()).isNotNull();
        assertThat(iterator.next()).isNotNull();
        assertThatThrownBy(iterator::hasNext).isInstanceOf(UnavailableException.class).hasMessage("down");
    }

    @Test
    void boundedMapLimitsConcurrencyAndKeepsOrder() {
        var inFlight = new AtomicInteger();
        var maxInFlight = new AtomicInteger();

        List<Integer> results = Bounded.map(IntStream.range(0, 40).boxed().toList(), 4, i -> {
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            inFlight.decrementAndGet();
            return i * 2;
        });

        assertThat(results).containsExactlyElementsOf(IntStream.range(0, 40).map(i -> i * 2).boxed().toList());
        assertThat(maxInFlight.get()).isBetween(2, 4);
    }

    private static BlobPage page(int page, Optional<String> next) {
        return new BlobPage(List.of(summary("p" + page + "-0"), summary("p" + page + "-1")), next);
    }

    private static BlobSummary summary(String key) {
        return new BlobSummary(BlobKey.of(key), 1, ETag.of("e"), Instant.EPOCH);
    }
}
