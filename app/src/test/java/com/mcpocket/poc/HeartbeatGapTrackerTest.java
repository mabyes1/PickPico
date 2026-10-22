package com.mcpocket.poc;

import static org.junit.Assert.assertEquals;

import org.json.JSONObject;
import org.junit.Test;

public class HeartbeatGapTrackerTest {
    @Test
    public void steadyTicksReportNoLongGap() throws Exception {
        HeartbeatGapTracker tracker = new HeartbeatGapTracker();
        tracker.tick(0L);
        tracker.tick(10_000L);
        tracker.tick(20_000L);
        JSONObject json = tracker.toJson();
        assertEquals(10_000L, json.getLong("maxTickGapMs"));
        assertEquals(0L, json.getLong("longGapCount"));
    }

    @Test
    public void frozenProcessShowsUpAsLongGap() throws Exception {
        HeartbeatGapTracker tracker = new HeartbeatGapTracker();
        tracker.tick(0L);
        tracker.tick(10_000L);
        tracker.tick(250_000L);
        tracker.tick(260_000L);
        JSONObject json = tracker.toJson();
        assertEquals(240_000L, json.getLong("maxTickGapMs"));
        assertEquals(240_000L, json.getLong("lastLongGapMs"));
        assertEquals(1L, json.getLong("longGapCount"));
        assertEquals(10_000L, json.getLong("lastTickGapMs"));
    }

    @Test
    public void reconnectBaselineIsNotCountedAsGap() throws Exception {
        HeartbeatGapTracker tracker = new HeartbeatGapTracker();
        tracker.tick(0L);
        tracker.resetBaseline();
        tracker.tick(600_000L);
        assertEquals(0L, tracker.toJson().getLong("longGapCount"));
    }
}
