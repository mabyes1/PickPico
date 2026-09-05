package com.mcpocket.poc;

import static org.junit.Assert.*;
import org.json.JSONObject;
import org.junit.Test;

public final class MediaReadinessTest {
    @Test public void lifecycleDriftAndRecoveryAreReflectedOnEveryCheck() throws Exception {
        for (String media : new String[]{"camera", "microphone"}) {
            assertTrue(state(true, true, media).getBoolean("available"));
            JSONObject lost = state(true, false, media);
            assertFalse(lost.getBoolean("available"));
            assertEquals("setup_required", lost.getString("state"));
            assertEquals("foreground_service_type", lost.getString("setupType"));
            assertEquals("open_app_to_refresh_media_access", lost.getString("setupAction"));
            assertTrue(lost.getBoolean("userInteractionRequired"));
            assertTrue(lost.getBoolean("requiresSetup"));
            assertFalse(lost.toString().contains("restart_node_from_app"));
            assertTrue(state(true, true, media).getBoolean("available"));
        }
    }

    @Test public void permissionRevocationWinsEvenWhenServiceTypeRemainsActive() throws Exception {
        for (boolean active : new boolean[]{false, true}) {
            JSONObject denied = state(false, active, "camera");
            assertFalse(denied.getBoolean("available"));
            assertEquals("runtime_permission", denied.getString("setupType"));
            assertEquals("grant_camera_permission", denied.getString("setupAction"));
        }
    }

    private JSONObject state(boolean permission, boolean foreground, String media) throws Exception {
        return AndroidCapabilityRegistry.mediaReadiness(new JSONObject(), permission, foreground, media);
    }
}
