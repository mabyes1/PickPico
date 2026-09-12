package com.mcpocket.poc;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class CallerRegistryTest {
    @Test public void registrationsAreIndependentAndCopiesCannotChangeSource() throws Exception {
        CallerRegistry registry = new CallerRegistry();
        String a = registry.register(new JSONObject().put("name", "A").put("type", "app")
                .put("packageName", "com.example.a")).getString("callerId");
        String b = registry.register(new JSONObject().put("name", "B").put("type", "remote")).getString("callerId");
        registry.resolve(a).put("name", "changed");
        assertEquals("A", registry.resolve(a).getString("name"));
        assertEquals("B", registry.resolve(b).getString("name"));
    }

    @Test public void evictedRegistrationDoesNotModifyTaskCopy() throws Exception {
        CallerRegistry registry = new CallerRegistry();
        JSONObject input = new JSONObject().put("name", "A").put("type", "remote");
        String id = registry.register(input).getString("callerId");
        JSONObject copy = registry.resolve(id);
        for (int i = 0; i < 50; i++) registry.register(input);
        assertThrows(CommandRuntime.CommandInputException.class, () -> registry.resolve(id));
        assertEquals("A", copy.getString("name"));
    }

    @Test public void invalidDestinationRejectedRatherThanRegistered() throws Exception {
        CallerRegistry registry = new CallerRegistry();
        assertThrows(CommandRuntime.CommandInputException.class, () -> registry.register(
                new JSONObject().put("name", "A").put("type", "app").put("returnUrl", "intent://unsafe")));
    }
}
