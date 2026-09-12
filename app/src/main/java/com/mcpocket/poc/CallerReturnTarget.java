package com.mcpocket.poc;

import org.json.JSONObject;
import java.net.URI;

/** Explicit task-owned return metadata. Never guesses from the foreground app. */
final class CallerReturnTarget {
    final String name, packageName, url;
    final boolean remote;

    private CallerReturnTarget(String name, String packageName, String url, boolean remote) {
        this.name = name; this.packageName = packageName; this.url = url; this.remote = remote;
    }

    static CallerReturnTarget fromTask(JSONObject task) {
        JSONObject context = task == null ? null : task.optJSONObject("context");
        JSONObject caller = context == null ? null : context.optJSONObject("caller");
        if (caller == null) return unknown();
        String pkg = caller.optString("packageName", "").trim();
        if (!pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")) pkg = "";
        String url = caller.optString("returnUrl", "").trim();
        try {
            URI uri = new URI(url);
            if (url.length() > 4096 || !"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null || uri.getUserInfo() != null) url = "";
        } catch (Exception ignored) { url = ""; }
        String name = caller.optString("name", "呼叫端").trim();
        if (name.isEmpty() || name.length() > 80) name = "呼叫端";
        return new CallerReturnTarget(name, pkg, url, "remote".equals(caller.optString("type")));
    }

    static CallerReturnTarget unknown() { return new CallerReturnTarget("呼叫端", "", "", false); }
    boolean available() { return !packageName.isEmpty() || !url.isEmpty(); }
    String label() { return available() ? "回到 " + name + "  →" : remote ? "此呼叫來自遠端 · 請回原裝置繼續" : "未提供返回位置"; }
}
