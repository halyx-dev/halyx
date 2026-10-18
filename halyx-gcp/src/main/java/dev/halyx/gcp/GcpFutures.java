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
package dev.halyx.gcp;

import com.google.api.core.ApiFuture;
import com.google.api.core.ApiFutureCallback;
import com.google.api.core.ApiFutures;
import com.google.api.gax.paging.AsyncPage;
import com.google.api.gax.rpc.ApiException;
import com.google.api.gax.rpc.StatusCode;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import dev.halyx.interceptor.AsyncInvocation;

/** Google's futures as {@code CompletableFuture}s, for the drivers' non-blocking paths. */
public final class GcpFutures {

    private GcpFutures() {
    }

    /** Completes as {@code future} does, on the thread that completes it; cancelling it cancels {@code future}. */
    public static <T> CompletableFuture<T> of(ApiFuture<T> future) {
        CompletableFuture<T> result = new CompletableFuture<>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                future.cancel(mayInterruptIfRunning);
                return super.cancel(mayInterruptIfRunning);
            }
        };
        ApiFutures.addCallback(future, new ApiFutureCallback<>() {
            @Override
            public void onFailure(Throwable error) {
                result.completeExceptionally(error);
            }

            @Override
            public void onSuccess(T value) {
                result.complete(value);
            }
        }, MoreExecutors.directExecutor());
        return result;
    }

    /** Every value of {@code page} and the pages after it, each fetched when the one before has arrived. */
    public static <T> CompletableFuture<List<T>> allPages(AsyncPage<T> page) {
        return collect(page, new ArrayList<>());
    }

    private static <T> CompletableFuture<List<T>> collect(AsyncPage<T> page, List<T> into) {
        page.getValues().forEach(into::add);
        if (!page.hasNextPage()) {
            return CompletableFuture.completedFuture(into);
        }
        return of(page.getNextPageAsync()).thenCompose(next -> collect(next, into));
    }

    /** True if {@code error}, unwrapped, is Google's answer with {@code code}. */
    public static boolean hasCode(Throwable error, StatusCode.Code code) {
        return AsyncInvocation.unwrap(error) instanceof ApiException e && e.getStatusCode().getCode() == code;
    }
}
