package com.rawwave.core;

import java.util.*;
import java.util.function.Consumer;

/** Per-operation resource ownership v1. Release actions must request cleanup without blocking.
 * Closing is terminal; resources delivered after close are released immediately. No SDK types.
 * An action returning proves only cleanup was requested, not physical resource release.
 */
public final class ResourceHandoff implements AutoCloseable {
    public static final int CONTRACT_VERSION = 1;
    private final Map<Object,Runnable> owned = new IdentityHashMap<>();
    private final Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Consumer<RuntimeException> errors;
    private boolean closed;
    public ResourceHandoff(Consumer<RuntimeException> errors) { this.errors=Objects.requireNonNull(errors); }
    public void accept(Object identity, Runnable release) {
        Objects.requireNonNull(identity); Objects.requireNonNull(release);
        synchronized(this) {
            if (!seen.add(identity)) return;
            if (!closed) { owned.putIfAbsent(identity,release); return; }
        }
        release(release);
    }
    @Override public void close() {
        List<Runnable> actions;
        synchronized(this) {
            if(closed) return;
            closed=true; actions=new ArrayList<>(owned.values()); owned.clear();
        }
        // Never run SDK callbacks while holding the ownership lock.
        for(Runnable action:actions) release(action);
    }
    private void release(Runnable action) {
        try { action.run(); } catch(RuntimeException error) {
            try { errors.accept(error); } catch(RuntimeException ignored) { /* cleanup must continue */ }
        }
    }
}
