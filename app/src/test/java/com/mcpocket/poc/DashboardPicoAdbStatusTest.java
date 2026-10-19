package com.mcpocket.poc;

import org.junit.Test;
import org.json.JSONObject;
import android.widget.TextView;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.util.ReflectionHelpers;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public final class DashboardPicoAdbStatusTest {
    @Test public void settingsAndSystemControlSeparateSavedPairingFromCurrentConnection() throws Exception {
        PicoAdbManager previous = ReflectionHelpers.getStaticField(PicoAdbManager.class, "instance");
        PicoAdbManager manager = mock(PicoAdbManager.class);
        JSONObject status = new JSONObject().put("state", "paired").put("paired", true)
                .put("lastConnectAt", "historic-success").put("connected", false)
                .put("connection", new JSONObject().put("state", "disconnected"));
        when(manager.status()).thenReturn(status);
        ReflectionHelpers.setStaticField(PicoAdbManager.class, "instance", manager);
        try (ActivityController<DashboardActivity> controller =
                     Robolectric.buildActivity(DashboardActivity.class).create()) {
            DashboardActivity activity = controller.get();
            ReflectionHelpers.callInstanceMethod(activity, "showPage", ReflectionHelpers.ClassParameter.from(int.class, 2));
            assertEquals("PAIRING · SAVED", view(activity, "settingsPicoAdbState").getText().toString());
            assertEquals("CONNECTION · NOT CONNECTED", view(activity, "settingsPicoAdbConnectionState").getText().toString());
            ReflectionHelpers.callInstanceMethod(activity, "showPage", ReflectionHelpers.ClassParameter.from(int.class, 6));
            assertEquals("PAIRING · SAVED", view(activity, "picoAdbPairingState").getText().toString());
            assertEquals("CONNECTION · NOT CONNECTED", view(activity, "picoAdbState").getText().toString());
            status.put("connected", true).getJSONObject("connection").put("state", "connected");
            ReflectionHelpers.callInstanceMethod(activity, "refreshStatus");
            assertEquals("CONNECTION · CONNECTED", view(activity, "picoAdbState").getText().toString());
            status.put("connected", false).getJSONObject("connection").put("state", "offline");
            ReflectionHelpers.callInstanceMethod(activity, "refreshStatus");
            assertEquals("PAIRING · SAVED", view(activity, "picoAdbPairingState").getText().toString());
            assertEquals("CONNECTION · OFFLINE", view(activity, "picoAdbState").getText().toString());
        } finally {
            ReflectionHelpers.setStaticField(PicoAdbManager.class, "instance", previous);
        }
    }

    private static TextView view(DashboardActivity activity, String field) {
        return ReflectionHelpers.getField(activity, field);
    }

    @Test public void homeCreationAndRefreshWorkBeforePicoAdbViewsAreInflated() {
        try (ActivityController<DashboardActivity> controller =
                     Robolectric.buildActivity(DashboardActivity.class).create()) {
            DashboardActivity activity = controller.get();
            assertNull(ReflectionHelpers.getField(activity, "settingsPicoAdbState"));
            assertNull(ReflectionHelpers.getField(activity, "picoAdbState"));
            assertNull(ReflectionHelpers.getField(activity, "picoAdbDetail"));

            ReflectionHelpers.callInstanceMethod(activity, "refreshStatus");
        }
    }
}
