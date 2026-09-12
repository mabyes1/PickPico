package com.mcpocket.poc;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** In-process cancellation boundary across Relay's loopback HTTP hop. */
final class RelayRequestScope implements AutoCloseable {
    static final String HEADER = "X-PickPico-Relay-Request";
    private static final ConcurrentMap<String, Lease> ACTIVE = new ConcurrentHashMap<>();
    private static final ThreadLocal<Lease> CURRENT = new ThreadLocal<>();
    private final Lease previous;

    private RelayRequestScope(Lease lease) {
        previous = CURRENT.get();
        if (lease == null) CURRENT.remove();
        else CURRENT.set(lease);
    }

    static Lease createLease() {
        Lease lease = new Lease();
        ACTIVE.put(lease.id, lease);
        return lease;
    }

    static RelayRequestScope enter(String id) {
        // Ordinary local requests have no relay lifetime attached.
        if (id == null || id.isEmpty()) return new RelayRequestScope(null);
        Lease lease = ACTIVE.get(id);
        if (lease == null) throw new CancelledException();
        lease.checkActive();
        return new RelayRequestScope(lease);
    }

    static void checkCurrent() {
        Lease lease = CURRENT.get();
        if (lease != null) lease.checkActive();
    }

    @Override public void close() {
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
    }

    static final class Lease implements AutoCloseable {
        final String id = UUID.randomUUID().toString();
        private volatile boolean cancelled;

        private void checkActive() {
            if (cancelled) throw new CancelledException();
        }

        @Override public void close() {
            cancelled = true;
            ACTIVE.remove(id, this);
        }
    }

    static final class CancelledException extends RuntimeException {
        CancelledException() {
            super("Relay request is no longer active; command was not started at this boundary");
        }
    }
}
