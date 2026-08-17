package com.mcpocket.poc;

import org.json.JSONException;
import org.json.JSONObject;

/** Android-facing actions that can be exposed as MCP tools. */
interface McpToolActions {
    JSONObject serverInfo(long callCount) throws JSONException;

    JSONObject phoneStatus(long callCount) throws JSONException;

    JSONObject phoneExec(String command, long callCount) throws JSONException;

    JSONObject execCommand(JSONObject arguments, long callCount) throws JSONException;

    JSONObject readProcessOutput(JSONObject arguments, long callCount) throws JSONException;

    JSONObject killProcessSession(JSONObject arguments, long callCount) throws JSONException;

    JSONObject workspaceInfo(long callCount) throws JSONException;

    JSONObject workspaceList(JSONObject arguments, long callCount) throws JSONException;

    JSONObject workspaceReadFile(JSONObject arguments, long callCount) throws JSONException;

    JSONObject workspaceWriteFile(JSONObject arguments, long callCount) throws JSONException;

    JSONObject phoneRing(String action, int durationSeconds, long callCount) throws JSONException;

    JSONObject phoneLock(long callCount) throws JSONException;

    JSONObject phoneEcho(String text, long callCount) throws JSONException;
}
