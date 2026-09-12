package com.mcpocket.poc;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.WebSocket;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public final class RelayRequestLifecycleTest {
    private MockedStatic<Looper> looper;
    private MockedConstruction<Handler> handlers;
    private RelayClient relay;
    private RelayClient.Listener listener;
    private WebSocket socket;
    private Call.Factory calls;
    private Call call;
    private ManualExecutor executor;

    @Before public void setUp() throws Exception {
        looper = mockStatic(Looper.class);
        handlers = mockConstruction(Handler.class);
        Context context = mock(Context.class);
        SharedPreferences prefs = mock(SharedPreferences.class);
        when(context.getApplicationContext()).thenReturn(context);
        when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(prefs);
        when(prefs.getString(anyString(), anyString())).thenReturn("test-existing-identity");
        listener = mock(RelayClient.Listener.class);
        executor = new ManualExecutor();
        calls = mock(Call.Factory.class);
        call = mock(Call.class);
        when(calls.newCall(any(Request.class))).thenReturn(call);
        when(call.execute()).thenAnswer(ignored -> response());
        relay = new RelayClient(context, "https://example.invalid", listener, executor, calls);
        socket = newSocket();
        field("webSocket").set(relay, socket);
    }

    @After public void tearDown() {
        if (relay != null) relay.close();
        if (handlers != null) handlers.close();
        if (looper != null) looper.close();
    }

    @Test public void currentRequestStillForwardsAndReturnsAResponse() throws Exception {
        enqueue(socket, "current");
        executor.runAll();
        verify(call).execute();
        verify(socket).send(contains("\"type\":\"response\""));
        assertEquals(0, pendingCount());
    }

    @Test public void queuedOldSocketRequestIsNotForwardedAfterReplacement() throws Exception {
        enqueue(socket, "queued");
        field("webSocket").set(relay, newSocket());
        executor.runAll();
        verifyNoInteractions(calls);
        assertEquals(0, pendingCount());
    }

    @Test public void recoveryDiscardsOldQueueAndNewSocketRemainsUsable() throws Exception {
        enqueue(socket, "old");
        recover(socket);
        assertEquals(0, pendingCount());
        WebSocket replacement = newSocket();
        field("webSocket").set(relay, replacement);
        enqueue(replacement, "new");
        executor.runAll();
        verify(calls, times(1)).newCall(any(Request.class));
        verify(replacement).send(contains("\"type\":\"response\""));
        verify(socket, never()).send(contains("\"type\":\"response\""));
        verify(listener, never()).onLoopbackProxyFailure(anyString(), any());
    }

    @Test public void closeCancelsQueuedWorkBeforeItCanReachLoopback() throws Exception {
        enqueue(socket, "stopped");
        relay.close();
        executor.runAll();
        verifyNoInteractions(calls);
        assertEquals(0, pendingCount());
        assertTrue(executor.isShutdown());
    }

    @Test public void disconnectWhileCallIsBeingCreatedPreventsExecute() throws Exception {
        when(calls.newCall(any(Request.class))).thenAnswer(ignored -> {
            recover(socket);
            field("webSocket").set(relay, newSocket());
            return call;
        });
        enqueue(socket, "pre-send-race");
        executor.runAll();
        verify(call).cancel();
        verify(call, never()).execute();
        verify(listener, never()).onLoopbackProxyFailure(anyString(), any());
    }

    @Test public void disconnectCancelsInFlightCallWithoutDamagingNewHealth() throws Exception {
        when(call.execute()).thenAnswer(ignored -> {
            recover(socket);
            field("webSocket").set(relay, newSocket());
            throw new IOException("cancelled old transport");
        });
        enqueue(socket, "in-flight");
        executor.runAll();
        verify(call).cancel();
        assertTrue(field("loopbackHealthy").getBoolean(relay));
        verify(listener, never()).onLoopbackProxyFailure(anyString(), any());
        verify(socket, never()).send(contains("\"type\":\"response\""));
    }

    @Test public void lateSuccessFromOldSocketIsNotPublished() throws Exception {
        when(call.execute()).thenAnswer(ignored -> {
            recover(socket);
            field("webSocket").set(relay, newSocket());
            return response();
        });
        enqueue(socket, "late-response");
        executor.runAll();
        verify(call).cancel();
        verify(socket, never()).send(contains("\"type\":\"response\""));
        verify(listener, never()).onLoopbackProxyFailure(anyString(), any());
    }

    @Test public void duplicatePendingEnvelopeIsOnlyExecutedOnce() throws Exception {
        enqueue(socket, "duplicate");
        enqueue(socket, "duplicate");
        executor.runAll();
        verify(calls, times(1)).newCall(any(Request.class));
        verify(call, times(1)).execute();
    }

    @Test public void realProxyFailureReportsUncertainExecution() throws Exception {
        when(call.execute()).thenThrow(new IOException("injected local connection failure"));
        enqueue(socket, "failed");
        executor.runAll();
        verify(listener).onLoopbackProxyFailure(eq("failed"), any(IOException.class));
        assertFalse(field("loopbackHealthy").getBoolean(relay));
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(socket, times(2)).send(sent.capture());
        JSONObject response = new JSONObject(sent.getAllValues().get(1));
        assertEquals(502, response.getInt("status"));
        assertEquals("unknown", new JSONObject(response.getString("body")).getString("executionState"));
    }

    @Test public void oldCleanupDoesNotRemoveNewRequestWithSameId() throws Exception {
        Call newCall = mock(Call.class);
        when(newCall.execute()).thenAnswer(ignored -> response());
        when(calls.newCall(any(Request.class))).thenReturn(call, newCall);
        WebSocket replacement = newSocket();
        when(call.execute()).thenAnswer(ignored -> {
            recover(socket);
            field("webSocket").set(relay, replacement);
            enqueue(replacement, "same-id");
            throw new IOException("old request cancelled");
        });
        enqueue(socket, "same-id");
        executor.runAll();
        verify(newCall).execute();
        verify(replacement).send(contains("\"type\":\"response\""));
        assertEquals(0, pendingCount());
    }

    private static WebSocket newSocket() {
        WebSocket socket = mock(WebSocket.class);
        when(socket.send(anyString())).thenReturn(true);
        return socket;
    }

    private void enqueue(WebSocket socket, String requestId) throws Exception {
        invoke("handleRelayMessage", new Class<?>[]{WebSocket.class, String.class}, socket,
                new JSONObject().put("type", "request").put("requestId", requestId)
                        .put("body", "{}").toString());
    }

    private void recover(WebSocket socket) throws Exception {
        invoke("recover", new Class<?>[]{WebSocket.class, String.class}, socket, "test network change");
    }

    private int pendingCount() throws Exception { return ((Map<?, ?>) field("pendingProxies").get(relay)).size(); }
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
    private static Response response() {
        return new Response.Builder().request(new Request.Builder().url("http://127.0.0.1/mcp").build())
                .protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(ResponseBody.create("{}", MediaType.get("application/json"))).build();
    }

    /** Deterministic scheduling: no network, sleeps or real Android loopers. */
    private static final class ManualExecutor extends AbstractExecutorService {
        private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        private boolean shutdown;
        @Override public void execute(Runnable command) {
            if (shutdown) throw new RejectedExecutionException();
            queue.add(command);
        }
        void runAll() throws Exception {
            while (!queue.isEmpty()) {
                Runnable work = queue.remove();
                work.run();
                if (work instanceof Future<?> && !((Future<?>) work).isCancelled()) {
                    ((Future<?>) work).get();
                }
            }
        }
        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() {
            shutdown = true;
            List<Runnable> remaining = new ArrayList<>(queue);
            queue.clear();
            return remaining;
        }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown && queue.isEmpty(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
    }
}
