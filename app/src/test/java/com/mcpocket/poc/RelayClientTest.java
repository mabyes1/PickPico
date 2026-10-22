package com.mcpocket.poc;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import okhttp3.WebSocket;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class RelayClientTest {
    private MockedStatic<Looper> looper;
    private MockedConstruction<Handler> handlers;
    private RelayClient relay;
    private RelayClient.Listener listener;
    private WebSocket socket;
    private ConnectivityManager connectivityManager;

    @Before public void setUp() throws Exception {
        looper = mockStatic(Looper.class);
        handlers = mockConstruction(Handler.class);
        Context context = mock(Context.class);
        SharedPreferences prefs = mock(SharedPreferences.class);
        connectivityManager = mock(ConnectivityManager.class);
        when(context.getApplicationContext()).thenReturn(context);
        when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(prefs);
        when(context.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(connectivityManager);
        when(prefs.getString(anyString(), anyString())).thenReturn("test-identity-already-created");
        listener = mock(RelayClient.Listener.class);
        relay = new RelayClient(context, "https://example.invalid", listener);
        socket = mock(WebSocket.class);
        field("webSocket").set(relay, socket);
    }

    @After public void tearDown() {
        if (relay != null) relay.close();
        if (handlers != null) handlers.close();
        if (looper != null) looper.close();
    }

    @Test public void heartbeatTimeoutSchedulesRecoveryWithoutCancelCallback() throws Exception {
        field("pendingHeartbeatNonce").set(relay, "missing-pong");
        invoke("handleHeartbeatTimeout", new Class<?>[]{WebSocket.class, String.class}, socket, "missing-pong");
        assertNull(field("webSocket").get(relay));
        verify(socket).cancel();
        verify(handlers.constructed().get(0)).postDelayed(any(Runnable.class), eq(1000L));
        verify(listener).onRelayState(eq("disconnected"), anyString(), anyString());
        // A late failure callback must not schedule a second connection.
        invoke("recover", new Class<?>[]{WebSocket.class, String.class}, socket, "late failure");
        verify(handlers.constructed().get(0), times(1)).postDelayed(any(Runnable.class), anyLong());
    }

    @Test public void screenOnWithoutSocketReconnectsImmediatelyInsteadOfWaitingForBackoff() throws Exception {
        field("webSocket").set(relay, null);
        field("reconnectAttempt").set(relay, 5);
        invoke("probeAfterWake", new Class<?>[]{});
        verify(handlers.constructed().get(0)).post(any(Runnable.class));
        assertEquals(0, field("reconnectAttempt").get(relay));
        assertEquals(1L, relay.diagnostics().getLong("wakeProbeCount"));
        assertFalse(relay.diagnostics().getString("lastWakeProbeAt").isEmpty());
    }

    @Test public void screenOnWithSocketStillCountsTheWakeProbe() throws Exception {
        invoke("probeAfterWake", new Class<?>[]{});
        assertEquals(1L, relay.diagnostics().getLong("wakeProbeCount"));
    }

    @Test public void shortFuseProbePingDoesNotCountAsAPeriodicHeartbeatTick() throws Exception {
        long[] now = {0L};
        try (MockedStatic<android.os.SystemClock> clock = mockStatic(android.os.SystemClock.class)) {
            clock.when(android.os.SystemClock::elapsedRealtime).thenAnswer(call -> now[0]);
            Class<?>[] types = {WebSocket.class, long.class, boolean.class};

            now[0] = 0L;
            invoke("sendHeartbeat", types, socket, 6_000L, false);
            verify(socket).send(contains("\"type\":\"ping\""));
            now[0] = 500_000L;
            field("pendingHeartbeatNonce").set(relay, null);
            invoke("sendHeartbeat", types, socket, 6_000L, false);
            assertEquals(0L, relay.diagnostics().getJSONObject("heartbeatGaps").getLong("maxTickGapMs"));

            // Periodic ticks are what the gap tracker measures.
            field("pendingHeartbeatNonce").set(relay, null);
            now[0] = 1_000_000L;
            invoke("sendHeartbeat", types, socket, 15_000L, true);
            field("pendingHeartbeatNonce").set(relay, null);
            now[0] = 1_500_000L;
            invoke("sendHeartbeat", types, socket, 15_000L, true);
            assertEquals(500_000L, relay.diagnostics().getJSONObject("heartbeatGaps").getLong("maxTickGapMs"));
        }
    }

    @Test public void oldSocketCannotDisconnectNewSocket() throws Exception {
        WebSocket old = mock(WebSocket.class);
        invoke("recover", new Class<?>[]{WebSocket.class, String.class}, old, "old close");
        assertSame(socket, field("webSocket").get(relay));
        verifyNoInteractions(listener);
        verify(handlers.constructed().get(0), never()).postDelayed(any(Runnable.class), anyLong());
    }

    @Test public void onlyMatchingPongResetsBackoff() throws Exception {
        field("reconnectAttempt").setInt(relay, 4);
        field("pendingHeartbeatNonce").set(relay, "expected");
        invoke("handleHeartbeatPong", new Class<?>[]{WebSocket.class, JSONObject.class}, socket, new JSONObject().put("nonce", "old"));
        assertEquals(4, field("reconnectAttempt").getInt(relay));
        invoke("handleHeartbeatPong", new Class<?>[]{WebSocket.class, JSONObject.class}, socket, new JSONObject().put("nonce", "expected"));
        assertEquals(0, field("reconnectAttempt").getInt(relay));
        verify(listener).onRelayState(eq("connected"), anyString(), anyString());
    }

    @Test public void stoppedClientDoesNotReconnectOnLateFailure() throws Exception {
        relay.close();
        clearInvocations(listener);
        invoke("recover", new Class<?>[]{WebSocket.class, String.class}, socket, "late failure");
        verifyNoInteractions(listener);
        verify(handlers.constructed().get(0), never()).postDelayed(any(Runnable.class), anyLong());
    }

    @Test public void losingActiveNetworkInvalidatesSocketAndStartsRecovery() throws Exception {
        Network network = mock(Network.class);
        field("activeNetwork").set(relay, network);

        invoke("handleNetworkLost", new Class<?>[]{Network.class}, network);

        assertNull(field("webSocket").get(relay));
        verify(socket).cancel();
        verify(listener).onRelayState(eq("disconnected"), anyString(), eq("active network lost"));
        verify(handlers.constructed().get(0)).postDelayed(any(Runnable.class), eq(1000L));
        JSONObject diagnostics = (JSONObject) invoke("diagnostics", new Class<?>[]{});
        assertEquals("none", diagnostics.getString("currentNetworkType"));
        assertEquals(1L, diagnostics.getLong("reconnectAttemptCount"));
        assertEquals("active network lost", diagnostics.getString("lastRelayDisconnectReason"));
    }

    @Test public void resetIdentityOnlyRemovesRelayIdentityKeys() {
        SharedPreferences prefs = mock(SharedPreferences.class);
        SharedPreferences.Editor editor = mock(SharedPreferences.Editor.class);
        when(prefs.edit()).thenReturn(editor);
        when(editor.remove(anyString())).thenReturn(editor);

        RelayClient.resetIdentity(prefs);

        verify(editor).remove("relay_node_id");
        verify(editor).remove("relay_node_secret");
        verify(editor).apply();
        verify(editor, never()).clear();
    }

    @Test public void loopbackProxyFailureIsExposedInDiagnostics() throws Exception {
        invoke(
                "recordLoopbackProxyError",
                new Class<?>[]{String.class, Exception.class},
                "request-123",
                new java.net.ConnectException("Connection refused"));

        JSONObject diagnostics = (JSONObject) invoke("diagnostics", new Class<?>[]{});
        assertEquals("request-123", diagnostics.getString("lastLoopbackProxyRequestId"));
        assertEquals("java.net.ConnectException", diagnostics.getString("lastLoopbackProxyErrorType"));
        assertEquals("Connection refused", diagnostics.getString("lastLoopbackProxyErrorMessage"));
        assertFalse(diagnostics.getString("lastLoopbackProxyErrorAt").isEmpty());
    }

    private Field field(String name) throws Exception {
        Field field = RelayClient.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
    private Object invoke(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = RelayClient.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(relay, args);
    }
}
