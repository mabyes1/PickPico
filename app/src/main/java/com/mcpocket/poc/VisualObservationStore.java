package com.mcpocket.poc;

import java.util.UUID;

/** One latest full-display screenshot, consumed by one action. No pixel-stability claim. */
final class VisualObservationStore {
    private String id, context;
    private long at;
    private int width, height;

    synchronized String record(String context, int width, int height, long now) {
        this.id = "screen-" + UUID.randomUUID();
        this.context = context;
        this.width = width;
        this.height = height;
        this.at = now;
        return id;
    }

    synchronized void invalidate() { id = null; }

    synchronized void consume(String expectedId, String currentContext, long now, double... coordinates) {
        String previous = id;
        id = null;
        if (previous == null || !previous.equals(expectedId) || !context.equals(currentContext)
                || now < at || now - at > 60_000L)
            throw new CommandRuntime.CommandInputException("STALE_SCREEN: take a new screen_capture; no action was performed");
        if (coordinates.length % 2 != 0) throw new IllegalArgumentException("Expected x/y pairs");
        for (int i = 0; i < coordinates.length; i += 2) {
            double x = coordinates[i], y = coordinates[i + 1];
            if (!Double.isFinite(x) || !Double.isFinite(y) || x < 0 || y < 0 || x >= width || y >= height)
                throw new CommandRuntime.CommandInputException("POINT_OUT_OF_SCREEN: use pixels in the latest screenshot; no action was performed");
        }
    }
}
