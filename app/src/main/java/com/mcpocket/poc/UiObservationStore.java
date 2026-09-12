package com.mcpocket.poc;

import java.util.LinkedHashMap;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Bounded, one-action observations; no Android dependencies so guards can be tested. */
final class UiObservationStore {
    private static final long MAX_AGE_MS = 120_000L;
    private final LinkedHashMap<String, Observation> observations = new LinkedHashMap<>();
    synchronized String record(String fingerprint, long now) {
        String id = "ui-" + UUID.randomUUID();
        observations.put(id, new Observation(fingerprint, now));
        while (observations.size() > 16) observations.remove(observations.keySet().iterator().next());
        return id;
    }
    synchronized void consume(String id, String fingerprint, long now) {
        Observation observation = observations.remove(id);
        if (observation == null || now < observation.at || now - observation.at > MAX_AGE_MS
                || !observation.fingerprint.equals(fingerprint))
            throw new CommandRuntime.CommandInputException("STALE_OBSERVATION: call ui_inspect and choose a current target; no action was performed");
    }
    static JSONObject compact(JSONObject node) throws JSONException {
        JSONObject result = new JSONObject().put("path", node.getString("path"));
        for (String key : new String[]{"text", "contentDescription", "viewId"})
            if (!node.optString(key).isEmpty()) result.put(key, node.optString(key));
        JSONArray actions = new JSONArray();
        if (node.optBoolean("clickable")) actions.put("click");
        if (node.optBoolean("longClickable")) actions.put("long_click");
        if (node.optBoolean("editable")) actions.put("type");
        if (node.optBoolean("scrollable")) actions.put("scroll");
        if (actions.length() > 0) result.put("actions", actions);
        if (!node.optBoolean("enabled", true)) result.put("enabled", false);
        if (!node.optBoolean("visible", true)) result.put("visible", false);
        if (node.optBoolean("focused")) result.put("focused", true);
        return result;
    }
    static boolean meaningful(JSONObject node) {
        return !node.optString("text").isEmpty() || !node.optString("contentDescription").isEmpty()
                || node.optBoolean("clickable") || node.optBoolean("editable")
                || node.optBoolean("scrollable") || node.optBoolean("longClickable");
    }
    private static final class Observation {
        final String fingerprint; final long at;
        Observation(String fingerprint, long at) { this.fingerprint = fingerprint; this.at = at; }
    }
}
