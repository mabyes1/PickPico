package com.mcpocket.poc;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.util.UUID;

final class AgentInboxStore {
    private static final String PREFS = "mcpocket_agent_inbox";
    private static final String KEY_ITEMS = "items";
    private static final int MAX_ITEMS = 50;

    private AgentInboxStore() {
    }

    static synchronized String add(Context context, String source, String title, String body) {
        String id = "inbox-" + UUID.randomUUID();
        try {
            JSONArray current = readArray(context);
            JSONArray next = new JSONArray();
            next.put(new JSONObject()
                    .put("id", id)
                    .put("source", source == null ? "agent" : source)
                    .put("title", title == null ? "" : title)
                    .put("body", body == null ? "" : body)
                    .put("createdAt", Instant.now().toString()));
            for (int index = 0; index < current.length() && next.length() < MAX_ITEMS; index++) {
                next.put(current.getJSONObject(index));
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_ITEMS, next.toString())
                    .apply();
        } catch (JSONException ignored) {
        }
        return id;
    }

    static synchronized JSONArray list(Context context) {
        try {
            return new JSONArray(readArray(context).toString());
        } catch (JSONException error) {
            return new JSONArray();
        }
    }

    static synchronized JSONObject get(Context context, String id) {
        JSONArray items = readArray(context);
        for (int index = 0; index < items.length(); index++) {
            JSONObject item = items.optJSONObject(index);
            if (item != null && id.equals(item.optString("id"))) {
                try {
                    return new JSONObject(item.toString());
                } catch (JSONException ignored) {
                    return item;
                }
            }
        }
        return null;
    }

    static synchronized int count(Context context) {
        return readArray(context).length();
    }

    static synchronized void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_ITEMS)
                .apply();
    }

    private static JSONArray readArray(Context context) {
        String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_ITEMS, "[]");
        try {
            return new JSONArray(raw == null ? "[]" : raw);
        } catch (JSONException error) {
            return new JSONArray();
        }
    }
}
