package com.mcpocket.poc;

import android.app.Application;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.time.Duration;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public final class HumanHelpDeliveryTest {
    private Application app;
    private KeyguardManager keyguard;
    private HumanHelpDelivery delivery;
    private MockedStatic<AgentAttention> attention;
    @Before public void setup() {
        app = RuntimeEnvironment.getApplication();
        keyguard = (KeyguardManager) app.getSystemService(Context.KEYGUARD_SERVICE);
        shadowOf(keyguard).setKeyguardLocked(false);
        attention = mockStatic(AgentAttention.class);
        attention.when(() -> AgentAttention.canStartActivityNow(app)).thenReturn(true);
        delivery = new HumanHelpDelivery(app);
    }
    @After public void cleanup() { delivery.stop(); attention.close(); }
    private void request(String state, long deadline) throws Exception {
        HumanHelpStore.save(app, new JSONObject().put("requestId", "delivery-test")
                .put("status", "waiting_human").put("deliveryStatus", state)
                .put("idleTimeoutSeconds", 120).put("createdAtEpochMs", System.currentTimeMillis())
                .put("expiresAtEpochMs", deadline));
    }
    private JSONObject current() throws Exception { return HumanHelpStore.load(app, "delivery-test"); }

    @Test public void unlockedLaunchDoesNotClaimDisplayedUntilWindowAcknowledges() throws Exception {
        request("received", System.currentTimeMillis() + 120000);
        delivery.start(); shadowOf(Looper.getMainLooper()).idle();
        Intent launch = shadowOf(app).getNextStartedActivity();
        assertEquals(HumanHelpActivity.class.getName(), launch.getComponent().getClassName());
        assertEquals("launching", current().getString("deliveryStatus"));
        assertFalse(current().has("displayedAt"));
        HumanHelpStore.markDisplayed(app, "delivery-test");
        assertEquals("displayed", current().getString("deliveryStatus"));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10));
        assertNull(shadowOf(app).getNextStartedActivity());
    }

    @Test public void lockedRequestSurvivesOriginalDeadlineAndLaunchesAfterUnlock() throws Exception {
        shadowOf(keyguard).setKeyguardLocked(true);
        request("waiting_unlock", System.currentTimeMillis() - 1000);
        delivery.start(); shadowOf(Looper.getMainLooper()).idle();
        assertNull(shadowOf(app).getNextStartedActivity());
        HumanHelpStore.markDisplayed(app, "delivery-test");
        assertEquals("waiting_unlock", current().getString("deliveryStatus"));
        assertEquals("waiting_human", current().getString("status"));
        shadowOf(keyguard).setKeyguardLocked(false);
        app.sendBroadcast(new Intent(Intent.ACTION_USER_PRESENT));
        shadowOf(Looper.getMainLooper()).idle();
        assertNotNull(shadowOf(app).getNextStartedActivity());
        assertTrue(current().getLong("expiresAtEpochMs") > System.currentTimeMillis());
    }

    @Test public void silentSystemRejectionIsBoundedAndReportedAsUnconfirmed() throws Exception {
        request("received", System.currentTimeMillis() + 120000);
        delivery.start(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(12));
        assertEquals(3, current().getInt("deliveryAttempts"));
        assertEquals("blocked", current().getString("deliveryStatus"));
        assertEquals("foreground_not_confirmed", current().getString("deliveryReason"));
        assertFalse(current().has("displayedAt"));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10));
        assertEquals(3, current().getInt("deliveryAttempts"));
    }

    @Test public void missingAccessReportsReasonAndPermissionRecoveryRetries() throws Exception {
        attention.when(() -> AgentAttention.canStartActivityNow(app)).thenReturn(false);
        request("received", System.currentTimeMillis() + 120000);
        delivery.start(); shadowOf(Looper.getMainLooper()).idle();
        assertNull(shadowOf(app).getNextStartedActivity());
        assertEquals("foreground_launch_access_required", current().getString("deliveryReason"));
        attention.when(() -> AgentAttention.canStartActivityNow(app)).thenReturn(true);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        assertNotNull(shadowOf(app).getNextStartedActivity());
    }
}
