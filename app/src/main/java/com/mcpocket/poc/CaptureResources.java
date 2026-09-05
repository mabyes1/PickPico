package com.mcpocket.poc;

import java.util.ArrayList;
import java.util.List;

/** One capture owns its resources, including resources delivered after cancellation. */
final class CaptureResources implements AutoCloseable {
    private final List<AutoCloseable> resources = new ArrayList<>();
    private boolean closed;

    synchronized boolean add(AutoCloseable resource) {
        if (closed) {
            closeQuietly(resource);
            return false;
        }
        for (AutoCloseable existing : resources) {
            if (existing == resource) return true;
        }
        resources.add(resource);
        return true;
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        for (int i = resources.size() - 1; i >= 0; i--) {
            closeQuietly(resources.get(i));
        }
        resources.clear();
    }

    private static void closeQuietly(AutoCloseable resource) {
        try {
            resource.close();
        } catch (Exception ignored) {
            // A failed close must not prevent the remaining resources from closing.
        }
    }
}
