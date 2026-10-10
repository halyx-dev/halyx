package org.kamranzafar.stratum.storage;

import java.util.Collections;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.function.Function;

/** Iterates every blob across pages, fetching each page lazily. */
final class PagingIterator implements Iterator<BlobSummary> {

    private final Function<ListOptions, BlobPage> fetch;
    private ListOptions next;
    private Iterator<BlobSummary> current = Collections.emptyIterator();

    PagingIterator(ListOptions first, Function<ListOptions, BlobPage> fetch) {
        this.next = first;
        this.fetch = fetch;
    }

    @Override
    public boolean hasNext() {
        while (!current.hasNext() && next != null) {
            BlobPage page = fetch.apply(next);
            current = page.items().iterator();
            next = page.nextPageToken().map(next::withPageToken).orElse(null);
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
}
