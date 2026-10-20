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
package dev.halyx.tck;

import dev.halyx.Halyx;
import dev.halyx.config.ProviderConfig;
import dev.halyx.storage.AsyncBlobStore;
import dev.halyx.storage.BlobContent;
import dev.halyx.storage.BlobKey;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The checks the conformance kits rely on to prove non-blocking drivers: what they accept, and the mistakes they
 * must catch, whatever the timing.
 */
class CompletingThreadsTest {

    /** Stands in for an SDK's own I/O threads. */
    private final ExecutorService sdk = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("sdk-io").factory());

    @Test
    void acceptsACallCompletedLaterOnTheSdksThread() throws Exception {
        CompletingThreads threads = new CompletingThreads();
        threads.track(() -> CompletableFuture.supplyAsync(() -> sleep(50), sdk)).get(5, TimeUnit.SECONDS);

        assertThat(threads.names()).containsExactly("sdk-io");
        threads.assertNonBlocking(1, Thread.currentThread());
    }

    @Test
    void acceptsACallTheSdkCompletedBeforeItReturnedWhileTheCallerNeverWaited() throws Exception {
        CompletingThreads threads = new CompletingThreads();
        // The race seen on CI: the SDK's thread completes the call before it returns. The caller spins, never parks.
        threads.track(() -> {
            CompletableFuture<String> call = CompletableFuture.supplyAsync(() -> "fast", sdk);
            while (!call.isDone()) {
                Thread.onSpinWait();
            }
            return call;
        }).get(5, TimeUnit.SECONDS);

        assertThat(threads.names()).containsExactly(CompletingThreads.COMPLETED_BEFORE_RETURNING);
        threads.assertNonBlocking(1, Thread.currentThread());
    }

    @Test
    void catchesADriverThatBlocksTheCaller() {
        CompletingThreads threads = new CompletingThreads();
        // A blocking SDK call made on the caller's thread, wrapped as an already-completed future.
        threads.track(() -> CompletableFuture.completedFuture(CompletableFuture.supplyAsync(() -> sleep(20), sdk).join()));

        assertThat(threads.names()).containsExactly(Thread.currentThread().getName());
        assertThatThrownBy(() -> threads.assertNonBlocking(1, Thread.currentThread())).isInstanceOf(AssertionError.class);
    }

    @Test
    void catchesADriverThatBlocksTheCallerBySleeping() {
        CompletingThreads threads = new CompletingThreads();
        threads.track(() -> CompletableFuture.completedFuture(sleep(20)));

        assertThatThrownBy(() -> threads.assertNonBlocking(1, Thread.currentThread())).isInstanceOf(AssertionError.class);
    }

    @Test
    void catchesTheFallbackEvenWhenItCompletesBeforeTheCallReturns() throws Exception {
        CompletingThreads threads = new CompletingThreads();
        // Halyx's fallback, finished before the call returns: no thread name to see, but the executor counted it.
        threads.track(() -> {
            CompletableFuture<String> call = CompletableFuture.supplyAsync(() -> "fallback", threads.executor());
            while (!call.isDone()) {
                Thread.onSpinWait();
            }
            return call;
        }).get(5, TimeUnit.SECONDS);

        assertThatThrownBy(() -> threads.assertNonBlocking(1, Thread.currentThread()))
                .isInstanceOf(AssertionError.class).hasMessageContaining("blocking fallback");
    }

    @Test
    void catchesARealDriverWithoutANonBlockingPath() throws Exception {
        // The memory provider's blob store has no non-blocking path: its asynchronous calls run on the fallback.
        CompletingThreads threads = new CompletingThreads();
        try (Halyx halyx = Halyx.builder().interceptor(threads).register("mem", ProviderConfig.of("memory")).build()) {
            AsyncBlobStore async = halyx.provider("mem").blobStore("threads").async(threads.executor());
            async.put(BlobKey.of("a.txt"), BlobContent.ofString("v")).get(5, TimeUnit.SECONDS);
            async.getBytes(BlobKey.of("a.txt")).get(5, TimeUnit.SECONDS);
        }

        assertThatThrownBy(() -> threads.assertNonBlocking(2, Thread.currentThread())).isInstanceOf(AssertionError.class);
    }

    private static String sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return "done";
    }
}
