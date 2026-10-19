package com.mcpocket.poc;

import android.app.Application;
import android.app.KeyguardManager;
import android.provider.Settings;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class DeveloperOptionsControlTest {
    @Test public void missingGrantLeavesSettingsUntouched() {
        Application app = RuntimeEnvironment.getApplication();
        shadowOf(app).denyPermissions(DeveloperOptionsControl.PERMISSION);
        Settings.Global.putInt(app.getContentResolver(), Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0);
        assertThrows(CommandRuntime.CommandInputException.class, () -> DeveloperOptionsControl.setEnabled(app, true));
        assertEquals(0, Settings.Global.getInt(app.getContentResolver(), Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, -1));
    }
    @Test public void directControlCanReenableAfterAllAdbTransportsAreOff() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        shadowOf(app).grantPermissions(DeveloperOptionsControl.PERMISSION);
        Settings.Global.putInt(app.getContentResolver(), Settings.Global.ADB_ENABLED, 1);
        app.getSharedPreferences("picoadb_state", 0).edit().putBoolean("paired", true).putString("last_pair_at", "keep").commit();
        assertTrue(DeveloperOptionsControl.setEnabled(app, false).getBoolean("verified"));
        assertFalse(DeveloperOptionsControl.status(app).getBoolean("usbDebuggingEnabled"));
        assertTrue(DeveloperOptionsControl.setEnabled(app, true).getBoolean("verified"));
        assertTrue(DeveloperOptionsControl.hasPermission(app));
        assertTrue(app.getSharedPreferences("picoadb_state", 0).getBoolean("paired", false));
        assertEquals("keep", app.getSharedPreferences("picoadb_state", 0).getString("last_pair_at", ""));
    }
    @Test public void lockedPhoneCannotOpenADebuggingChannel() {
        Application app = RuntimeEnvironment.getApplication();
        shadowOf(app).grantPermissions(DeveloperOptionsControl.PERMISSION);
        shadowOf((KeyguardManager) app.getSystemService(Application.KEYGUARD_SERVICE)).setKeyguardLocked(true);
        assertThrows(CommandRuntime.CommandInputException.class, () -> DeveloperOptionsControl.setEnabled(app, true));
    }
}
