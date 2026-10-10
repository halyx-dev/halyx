package org.kamranzafar.cirrus.spring.autoconfigure.config;

import org.springframework.context.ApplicationEvent;

import java.util.Set;

/**
 * Published when imported parameters changed and the {@code Environment} was updated. Listen for it to
 * re-read values (for example, to rebuild a connection pool after a password rotation).
 */
public class CirrusParametersChangedEvent extends ApplicationEvent {

    private final String store;
    private final Set<String> keys;

    public CirrusParametersChangedEvent(Object source, String store, Set<String> keys) {
        super(source);
        this.store = store;
        this.keys = Set.copyOf(keys);
    }

    /** The {@code cirrus.parameter-stores} name of the store that changed. */
    public String store() {
        return store;
    }

    /** Property keys that were added, changed or removed. */
    public Set<String> keys() {
        return keys;
    }
}
