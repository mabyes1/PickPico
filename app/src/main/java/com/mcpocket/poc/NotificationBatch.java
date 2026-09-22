package com.mcpocket.poc;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.LinkedHashSet;
import java.util.Set;

/** Small, testable snapshot/result contract. Never equate cancellation with removal. */
final class NotificationBatch {
    static JSONObject compact(JSONObject source) throws JSONException {
        JSONObject out = new JSONObject();
        for (String key : new String[]{"key", "packageName", "appLabel", "postTime", "title", "clearable", "ongoing"})
            out.put(key, source.opt(key));
        String body = source.optString("bigText");
        if (body.isEmpty()) body = source.optString("text");
        out.put("text", body.length() > 600 ? body.substring(0, 600) : body);
        out.put("textTruncated", body.length() > 600);
        return out;
    }

    static Set<String> candidates(JSONArray snapshot) throws JSONException {
        Set<String> keys = new LinkedHashSet<>();
        for (int i = 0; i < snapshot.length(); i++) {
            JSONObject n = snapshot.getJSONObject(i);
            if (n.optBoolean("clearable")) keys.add(n.getString("key"));
        }
        return keys;
    }

    static JSONObject verify(Set<String> requested, Set<String> remaining) throws JSONException {
        JSONArray removed = new JSONArray(), pending = new JSONArray();
        for (String key : requested) (remaining.contains(key) ? pending : removed).put(key);
        return new JSONObject().put("requestedCount", requested.size())
                .put("confirmedAbsentCount", removed.length()).put("remainingCount", pending.length())
                .put("remainingKeys", pending).put("verified", pending.length() == 0)
                .put("status", pending.length() == 0 ? "completed" : "partial");
    }
}
