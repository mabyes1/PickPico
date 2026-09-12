package com.mcpocket.poc;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Iterator;

/** Validates the JSON Schema subset used by our tools before approval or dispatch. */
final class ToolSchemaValidator {
    private ToolSchemaValidator() { }

    static final class Invalid extends CommandRuntime.CommandInputException {
        final String field;
        final String expected;
        Invalid(String field, String expected) {
            super(field + ": expected " + expected);
            this.field = field;
            this.expected = expected;
        }
    }

    static void validate(JSONObject schema, Object value, String path) {
        String type = schema.optString("type", "");
        boolean valid;
        switch (type) {
            case "object": valid = value instanceof JSONObject; break;
            case "array": valid = value instanceof JSONArray; break;
            case "string": valid = value instanceof String; break;
            case "boolean": valid = value instanceof Boolean; break;
            case "integer":
                valid = value instanceof Number && Double.isFinite(((Number) value).doubleValue())
                        && ((Number) value).doubleValue() == Math.rint(((Number) value).doubleValue()); break;
            case "number": valid = value instanceof Number && Double.isFinite(((Number) value).doubleValue()); break;
            default: valid = true;
        }
        if (!valid) throw new Invalid(path, type);
        JSONArray choices = schema.optJSONArray("enum");
        if (choices != null) {
            boolean found = false;
            for (int i = 0; i < choices.length(); i++) found |= choices.opt(i) instanceof Number && value instanceof Number
                    ? ((Number) choices.opt(i)).doubleValue() == ((Number) value).doubleValue() : choices.opt(i).equals(value);
            if (!found) throw new Invalid(path, "one of " + choices);
        }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            JSONObject props = schema.optJSONObject("properties");
            JSONArray required = schema.optJSONArray("required");
            if (required != null) for (int i = 0; i < required.length(); i++) {
                String key = required.optString(i);
                if (!object.has(key)) throw new Invalid(path + "." + key, "required field");
            }
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject child = props == null ? null : props.optJSONObject(key);
                if (child != null) validate(child, object.opt(key), path + "." + key);
                else if (Boolean.FALSE.equals(schema.opt("additionalProperties")))
                    throw new Invalid(path + "." + key, "no unknown fields");
                else if (schema.optJSONObject("additionalProperties") != null)
                    validate(schema.optJSONObject("additionalProperties"), object.opt(key), path + "." + key);
            }
            if (object.length() < schema.optInt("minProperties", 0)) throw new Invalid(path, "at least one selector field");
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            if (array.length() < schema.optInt("minItems", 0) || array.length() > schema.optInt("maxItems", Integer.MAX_VALUE))
                throw new Invalid(path, "array length " + schema.optInt("minItems", 0) + ".." + schema.optInt("maxItems", Integer.MAX_VALUE));
            JSONObject items = schema.optJSONObject("items");
            if (items != null) for (int i = 0; i < array.length(); i++) validate(items, array.opt(i), path + "[" + i + "]");
        } else if (value instanceof String) {
            String text = (String) value;
            int length = text.codePointCount(0, text.length());
            if (length < schema.optInt("minLength", 0) || length > schema.optInt("maxLength", Integer.MAX_VALUE))
                throw new Invalid(path, "string length " + schema.optInt("minLength", 0) + ".." + schema.optInt("maxLength", Integer.MAX_VALUE));
            if (schema.has("pattern") && !java.util.regex.Pattern.compile(schema.optString("pattern")).matcher(text).find())
                throw new Invalid(path, "pattern " + schema.optString("pattern"));
        } else if (value instanceof Number) {
            double number = ((Number) value).doubleValue();
            if (number < schema.optDouble("minimum", -Double.MAX_VALUE) || number > schema.optDouble("maximum", Double.MAX_VALUE))
                throw new Invalid(path, "number " + schema.optDouble("minimum", -Double.MAX_VALUE) + ".." + schema.optDouble("maximum", Double.MAX_VALUE));
        }
    }
}
