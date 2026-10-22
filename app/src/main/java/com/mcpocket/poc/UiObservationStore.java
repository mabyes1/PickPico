package com.mcpocket.poc;

import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Set;
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
    /** The passive Pico orb changes on every tool call; it is not the app being operated. */
    static JSONObject guardSnapshot(JSONObject snapshot) throws JSONException {
        JSONArray windows = snapshot.optJSONArray("windows");
        if (windows == null) return snapshot;
        Set<Integer> ignored = new HashSet<>();
        JSONArray guardedWindows = new JSONArray();
        for (int i = 0; i < windows.length(); i++) {
            JSONObject window = windows.getJSONObject(i);
            if (i > 0 && window.optInt("type") == 3
                    && "com.mcpocket.poc".equals(window.optString("packageName"))
                    && !window.optBoolean("active") && !window.optBoolean("focused")) {
                ignored.add(window.getInt("id"));
            } else guardedWindows.put(window);
        }
        if (ignored.isEmpty()) return snapshot;
        JSONArray guardedNodes = new JSONArray();
        JSONArray nodes = snapshot.getJSONArray("nodes");
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.getJSONObject(i);
            String path = node.optString("path");
            boolean skip = false;
            for (int id : ignored) if (path.startsWith("w" + id + "/")) { skip = true; break; }
            if (!skip) guardedNodes.put(node);
        }
        return new JSONObject(snapshot.toString()).put("windows", guardedWindows).put("nodes", guardedNodes);
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
