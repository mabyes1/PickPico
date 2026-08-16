package com.mcpocket.poc;

import org.json.JSONException;
import org.json.JSONObject;

/** Android-facing actions that can be exposed as MCP tools. */
interface McpToolActions {
    JSONObject serverInfo(long callCount) throws JSONException;

    JSONObject phoneStatus(long callCount) throws JSONException;

    JSONObject phoneExec(String command, long callCount) throws JSONException;

    JSONObject phoneRing(String action, int durationSeconds, long callCount) throws JSONException;

    JSONObject phoneEcho(String text, long callCount) throws JSONException;
}
