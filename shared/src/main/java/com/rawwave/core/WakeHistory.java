package com.rawwave.core;

import java.util.*;

/** Recent-attempt deduplication, not authorization or indefinite replay protection. */
public final class WakeHistory {
    private final LinkedHashSet<String> recent = new LinkedHashSet<>();
    public WakeHistory(Collection<String> saved) { for (String id : saved) accept(id); }
    public boolean accept(String id) {
        Objects.requireNonNull(id);
        if (recent.contains(id)) return false;
        recent.add(id);
        while (recent.size() > 64) recent.remove(recent.iterator().next());
        return true;
    }
    public List<String> snapshot() { return List.copyOf(recent); }
}
