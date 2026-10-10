package org.kamranzafar.cirrus.storage;

import java.util.Collections;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

/**
 * Iterates every blob across pages. While the caller works through one page, the next is already being
 * fetched on a virtual thread, so a long listing waits for the network about once per page instead of
 * once per page plus the caller's processing time. At most one page is fetched ahead.
 */
final class PagingIterator implements Iterator<BlobSummary> {

    private final ListOptions base;
    private final Function<ListOptions, BlobPage> fetch;
    private boolean started;
    private CompletableFuture<BlobPage> nextPage;
    private Iterator<BlobSummary> current = Collections.emptyIterator();

    PagingIterator(ListOptions first, Function<ListOptions, BlobPage> fetch) {
        this.base = first;
        this.fetch = fetch;
    }

    @Override
    public boolean hasNext() {
        while (!current.hasNext() && (!started || nextPage != null)) {
            BlobPage page = started ? await(nextPage) : fetch.apply(base);
            started = true;
            current = page.items().iterator();
            nextPage = page.nextPageToken()
                    .map(token -> CompletableFuture.supplyAsync(() -> fetch.apply(base.withPageToken(token)),
                            PagingIterator::startVirtual))
                    .orElse(null);
        }
        return current.hasNext();
    }

    @Override
    public BlobSummary next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        return current.next();
    }

    private static BlobPage await(CompletableFuture<BlobPage> page) {
        try {
            return page.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (e.getCause() instanceof Error error) {
                throw error;
            }
            throw e;
        }
    }

    private static void startVirtual(Runnable task) {
        Thread.ofVirtual().name("cirrus-list-prefetch").start(task);
    }
}
