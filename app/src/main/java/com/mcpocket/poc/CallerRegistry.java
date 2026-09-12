package com.mcpocket.poc;

import org.json.JSONObject;
import org.json.JSONException;
import java.util.LinkedHashMap;
import java.util.UUID;

/** Bounded process-local registrations; tasks copy metadata instead of retaining a mutable pointer. */
final class CallerRegistry {
    private final LinkedHashMap<String, JSONObject> callers = new LinkedHashMap<>();

    synchronized JSONObject register(JSONObject input) throws JSONException {
        String name = input.optString("name", "").trim();
        String type = input.optString("type", "");
        if (name.isEmpty() || name.length() > 80 || !(type.equals("app") || type.equals("remote")))
            throw new CommandRuntime.CommandInputException("Provide name (1-80 characters) and type app or remote");
        CallerReturnTarget target = CallerReturnTarget.fromTask(new JSONObject()
                .put("context", new JSONObject().put("caller", input)));
        if ((!input.optString("packageName", "").isEmpty() && target.packageName.isEmpty())
                || (!input.optString("returnUrl", "").isEmpty() && target.url.isEmpty()))
            throw new CommandRuntime.CommandInputException("Invalid Android package or HTTPS returnUrl");
        JSONObject caller = new JSONObject().put("name", name).put("type", type)
                .put("packageName", target.packageName).put("returnUrl", target.url);
        String id = "caller-" + UUID.randomUUID();
        callers.put(id, caller);
        while (callers.size() > 50) callers.remove(callers.keySet().iterator().next());
        return new JSONObject().put("callerId", id).put("canReturn", target.available())
                .put("nextStep", "Pass callerId to task_create. Registration expires on node restart or eviction; register again if unknown. This identifies a supplied destination, not an authenticated app identity.");
    }

    synchronized JSONObject resolve(String id) throws JSONException {
        JSONObject caller = callers.get(id);
        if (caller == null) throw new CommandRuntime.CommandInputException("Unknown callerId; call caller_register again");
        return new JSONObject(caller.toString());
    }
}
