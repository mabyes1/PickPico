package com.mcpocket.poc;

import org.json.JSONObject;

/** Cross-field validation runs before approval, for both direct and dynamic callers. */
final class VisualUiRequest {
    private VisualUiRequest() {}

    static void validate(String id, JSONObject args) {
        if ("ui.action".equals(id)) {
            String action = args.optString("action");
            boolean global = "back".equals(action) || "home".equals(action) || "recents".equals(action);
            boolean point = args.has("point");
            if (global) {
                if (point || args.has("selector")) fail("selector/point", "no target for global actions");
            } else {
                if (args.has("selector") == point) fail("selector/point", "exactly one target");
                if (point && !"click".equals(action) && !"long_click".equals(action))
                    fail("action", "click or long_click for a point");
            }
            if (point) screenObservation(args);
        } else if ("ui.scroll".equals(id)) {
            if (args.has("swipe")) {
                if (args.has("selector") || args.has("direction")) fail("swipe", "no selector/direction with swipe");
                JSONObject swipe = args.optJSONObject("swipe");
                if (swipe != null && swipe.optJSONObject("start") != null && swipe.optJSONObject("end") != null) {
                    JSONObject a = swipe.optJSONObject("start"), b = swipe.optJSONObject("end");
                    if (a.optDouble("x") == b.optDouble("x") && a.optDouble("y") == b.optDouble("y"))
                        fail("swipe.end", "different from start");
                }
                screenObservation(args);
            }
        } else if ("ui.type".equals(id)) {
            boolean focused = args.optBoolean("focused", false);
            if (focused == args.has("selector")) fail("selector/focused", "one selector or focused=true");
            if (focused) {
                if (args.has("append")) fail("append", "use textMode with focused input");
                if (!args.has("textMode")) fail("textMode", "insert or replace for focused input");
                screenObservation(args);
            } else if (args.has("textMode")) fail("textMode", "only with focused=true");
        }
    }

    private static void screenObservation(JSONObject args) {
        if (!args.optString("observationId").startsWith("screen-"))
            fail("observationId", "fresh screen_capture observationId for visual actions");
    }

    private static void fail(String field, String expected) {
        throw new ToolSchemaValidator.Invalid("arguments." + field, expected);
    }
}
