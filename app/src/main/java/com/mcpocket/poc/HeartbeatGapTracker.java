package com.mcpocket.poc;

import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;

/**
 * Records how far apart relay heartbeat ticks really are. The timer is meant to fire
 * every few seconds; a long gap means the process was frozen (Doze, standby bucket,
 * CPU suspend) rather than the relay or network misbehaving.
 */
final class HeartbeatGapTracker {
    static final long LONG_GAP_MS = 30_000L;

    private long lastTickMs = -1L;
    private long maxGapMs;
    private long lastGapMs;
    private long longGapCount;
    private long lastLongGapMs;
    private String lastLongGapAt = "";

    /** @param nowMs monotonic time that keeps counting while the CPU sleeps (elapsedRealtime). */
    synchronized void tick(long nowMs) {
        if (lastTickMs >= 0L) {
            long gap = Math.max(0L, nowMs - lastTickMs);
            lastGapMs = gap;
            if (gap > maxGapMs) maxGapMs = gap;
            if (gap >= LONG_GAP_MS) {
                longGapCount++;
                lastLongGapMs = gap;
                lastLongGapAt = Instant.now().toString();
            }
        }
        lastTickMs = nowMs;
    }

    /** A new socket restarts the loop, so the first tick after it must not count as a gap. */
    synchronized void resetBaseline() {
        lastTickMs = -1L;
    }

    synchronized JSONObject toJson() throws JSONException {
        return new JSONObject()
                .put("lastTickGapMs", lastGapMs)
                .put("maxTickGapMs", maxGapMs)
                .put("longGapCount", longGapCount)
                .put("lastLongGapMs", lastLongGapMs)
                .put("lastLongGapAt", lastLongGapAt)
                .put("longGapThresholdMs", LONG_GAP_MS);
    }
}
