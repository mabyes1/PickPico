package com.mcpocket.poc;

import android.os.Looper;
import android.os.PowerManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;
import java.time.Duration;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class AgentScreenServiceTest {
    @Test public void unfinishedTaskAndHungCommandReleaseWithoutAnotherCall() {
        McpNodeService service = Robolectric.buildService(McpNodeService.class).get();
        service.onAgentTasksChanged(1);
        service.onAgentCommandStarted("process.exec");
        shadowOf(Looper.getMainLooper()).idle();
        PowerManager.WakeLock lock = ReflectionHelpers.getField(service, "agentScreenWakeLock");
        assertNotNull(lock);
        assertTrue(lock.isHeld());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(3));
        assertFalse(lock.isHeld());
        assertNull(ReflectionHelpers.getField(service, "agentScreenKeepAwakeView"));
        service.onAgentCommandFinished("process.exec");
        shadowOf(Looper.getMainLooper()).idle();
        assertNull(ReflectionHelpers.getField(service, "agentScreenWakeLock"));
        assertEquals(1, (int) ReflectionHelpers.getField(service, "awakeTaskCount"));
    }
}
