package com.mcpocket.poc;

import org.json.JSONException;
import org.json.JSONObject;

/** Request-local display identity. This is self-reported metadata, not authentication. */
final class AgentIdentity implements AutoCloseable {
    static final String DESCRIPTION = "Required: your actual model name/version for the phone UI. "
            + "Use the model identity exposed by your runtime, not just a client/app name. "
            + "If the model is not exposed, explicitly use 'unknown (model not exposed)'; never invent a model.";
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
    private final String previous;

    private AgentIdentity(String value) {
        previous = CURRENT.get();
        if (value.isEmpty()) CURRENT.remove(); else CURRENT.set(value);
    }

    static AgentIdentity enter(String value) { return new AgentIdentity(value); }
    static String current() { String value = CURRENT.get(); return value == null ? "" : value; }

    static String require(JSONObject arguments) {
        Object supplied = arguments == null ? null : arguments.opt("agent");
        if (!(supplied instanceof String)) throw invalid();
        String value = ((String) supplied).trim();
        boolean visible = false;
        for (int i = 0; i < value.length();) {
            int c = value.codePointAt(i);
            i += Character.charCount(c);
            if (!Character.isWhitespace(c) && !Character.isSpaceChar(c)
                    && !Character.isISOControl(c) && Character.getType(c) != Character.FORMAT
                    && Character.getType(c) != Character.SURROGATE) visible = true;
        }
        if (!visible || value.length() > 160) throw invalid();
        return value;
    }

    private static CommandRuntime.CommandInputException invalid() {
        return new CommandRuntime.CommandInputException(
                "agent is required: supply a non-blank string (1-160 characters) containing your actual "
                        + "model name/version for the phone UI. If unavailable, explicitly use "
                        + "'unknown (model not exposed)'; do not invent one.");
    }

    static JSONObject schema() throws JSONException {
        return new JSONObject().put("type", "string").put("minLength", 1)
                .put("maxLength", 160).put("pattern", "\\S").put("description", DESCRIPTION);
    }

    @Override public void close() {
        if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
    }
}
