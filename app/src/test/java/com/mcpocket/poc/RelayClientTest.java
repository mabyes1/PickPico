package com.mcpocket.poc;

import android.content.Context;
import android.content.SharedPreferences;
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

    @Before public void setUp() throws Exception {
        looper = mockStatic(Looper.class);
        handlers = mockConstruction(Handler.class);
        Context context = mock(Context.class);
        SharedPreferences prefs = mock(SharedPreferences.class);
        when(context.getApplicationContext()).thenReturn(context);
        when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(prefs);
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

    private Field field(String name) throws Exception {
        Field field = RelayClient.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
    private void invoke(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = RelayClient.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        method.invoke(relay, args);
    }
}
