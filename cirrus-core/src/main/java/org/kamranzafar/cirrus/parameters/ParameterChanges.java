package org.kamranzafar.cirrus.parameters;

import java.util.Map;
import java.util.Set;

/**
 * What changed between two polls of a watched prefix, keyed by name.
 *
 * @param added   values that appeared
 * @param updated values with a new version (rotations land here)
 * @param removed names that disappeared
 */
public record ParameterChanges(Map<String, Parameter> added, Map<String, Parameter> updated, Set<String> removed) {

    public ParameterChanges {
        added = Map.copyOf(added);
        updated = Map.copyOf(updated);
        removed = Set.copyOf(removed);
    }

    public boolean isEmpty() {
        return added.isEmpty() && updated.isEmpty() && removed.isEmpty();
    }
}
