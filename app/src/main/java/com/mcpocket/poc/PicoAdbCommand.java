package com.mcpocket.poc;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** ADB argument vectors, never a host shell command. Local paths use the workspace. */
final class PicoAdbCommand {
    private PicoAdbCommand() { }

    static JSONObject schema(boolean shell) throws JSONException {
        JSONObject properties = new JSONObject()
                .put("timeoutMs", new JSONObject().put("type", "integer")
                        .put("minimum", 100).put("maximum", 120000).put("default", 30000))
                .put("maxOutputBytes", new JSONObject().put("type", "integer")
                        .put("minimum", 1024).put("maximum", 262144).put("default", 16384)
                        .put("description", "Inline UTF-8 output limit. Complete output is saved to workspacePath."));
        String key = shell ? "command" : "args";
        properties.put(key, shell
                ? new JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 8192)
                        .put("description", "Android ADB shell command, including pipes and shell syntax. Runs with shell UID 2000 permissions.")
                : new JSONObject().put("type", "array").put("minItems", 1).put("maxItems", 128)
                        .put("items", new JSONObject().put("type", "string").put("maxLength", 8192))
                        .put("description", "ADB command and arguments, without adb or global selectors. Target phone is supplied automatically. Local push/pull/install paths are relative to the PickPico workspace. Examples: [push, file.txt, /sdcard/Download/file.txt], [pull, /sdcard/Download/file.txt, copy.txt], [install, -r, app.apk], [logcat, -d]."));
        return new JSONObject().put("type", "object").put("properties", properties)
                .put("required", new JSONArray().put(key)).put("additionalProperties", false);
    }

    static void validate(JSONObject arguments, boolean shell) {
        if (shell) {
            String command = arguments.optString("command", "");
            if (command.trim().isEmpty() || command.indexOf('\0') >= 0)
                throw new CommandRuntime.CommandInputException("ADB shell command must be non-empty and contain no NUL");
        } else {
            JSONArray args = arguments.optJSONArray("args");
            if (args == null || args.length() == 0 || args.optString(0).trim().isEmpty()
                    || args.optString(0).startsWith("-") || "adb".equals(args.optString(0)))
                throw new CommandRuntime.CommandInputException("Start args with an ADB command; the engine and target selector are supplied automatically");
            for (int i = 0; i < args.length(); i++) {
                if (args.optString(i).indexOf('\0') >= 0)
                    throw new CommandRuntime.CommandInputException("ADB arguments must contain no NUL");
            }
        }
    }

    static List<String> argv(JSONObject arguments, boolean shell, String serial) throws JSONException {
        List<String> result = new ArrayList<>();
        result.add("-s"); result.add(serial);
        if (shell) {
            result.add("shell"); result.add("-T"); result.add("-n");
            result.add(arguments.getString("command"));
        } else {
            JSONArray args = arguments.getJSONArray("args");
            for (int i = 0; i < args.length(); i++) result.add(args.getString(i));
        }
        return result;
    }
}
