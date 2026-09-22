package com.mcpocket.poc;

import android.content.Context;
import org.json.JSONObject;

/** User-selected fallback, independent of the ephemeral task registry. */
final class CallerReturnSettings {
    static CallerReturnTarget load(Context context) {
        try {
            return CallerReturnTarget.fromTask(new JSONObject().put("context", new JSONObject()
                    .put("caller", new JSONObject(context.getSharedPreferences("caller_return", 0)
                            .getString("target", "{}")))));
        } catch (Exception ignored) { return CallerReturnTarget.unknown(); }
    }

    static void save(Context context, String name, String packageName) {
        JSONObject target = new JSONObject();
        try { target.put("name", name).put("packageName", packageName); }
        catch (Exception error) { throw new IllegalArgumentException(error); }
        context.getSharedPreferences("caller_return", 0).edit().putString("target", target.toString()).apply();
    }
}
