package com.mcpocket.poc;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public final class RelayRequestScopeTest {
    private McpHttpServer server;
    private int port;

    @After public void tearDown() { if (server != null) server.stop(); }

    @Test public void cancelledLeaseRejectsBothExistingAndNewScopes() {
        try (RelayRequestScope.Lease lease = RelayRequestScope.createLease();
             RelayRequestScope scope = RelayRequestScope.enter(lease.id)) {
            RelayRequestScope.checkCurrent();
            lease.close();
            assertThrows(RelayRequestScope.CancelledException.class, RelayRequestScope::checkCurrent);
            assertThrows(RelayRequestScope.CancelledException.class, () -> RelayRequestScope.enter(lease.id));
        }
        RelayRequestScope.checkCurrent();
    }

    @Test public void localRequestsAndOtherLeasesAreIndependent() {
        try (RelayRequestScope.Lease first = RelayRequestScope.createLease();
             RelayRequestScope.Lease second = RelayRequestScope.createLease();
             RelayRequestScope scope = RelayRequestScope.enter(first.id)) {
            first.close();
            try (RelayRequestScope local = RelayRequestScope.enter(null)) {
                RelayRequestScope.checkCurrent();
            }
            assertThrows(RelayRequestScope.CancelledException.class, RelayRequestScope::checkCurrent);
            try (RelayRequestScope other = RelayRequestScope.enter(second.id)) {
                RelayRequestScope.checkCurrent();
            }
        }
        RelayRequestScope.checkCurrent();
    }

    @Test public void unknownRelayLeaseCannotReachCommandExecution() {
        assertThrows(RelayRequestScope.CancelledException.class,
                () -> RelayRequestScope.enter("unknown-unit-test-lease"));
        RelayRequestScope.checkCurrent();
    }

    @Test public void disconnectDuringApprovalPreventsTheApprovedSideEffect() throws Exception {
        McpToolActions actions = mock(McpToolActions.class, CALLS_REAL_METHODS);
        when(actions.approvalMode()).thenReturn(McpocketPolicySettings.APPROVAL_AUTO);
        when(actions.workspaceWriteFile(any(), anyLong())).thenReturn(new JSONObject().put("bytesWritten", 1));
        CommandRuntime runtime = new CommandRuntime(actions);
        try (RelayRequestScope.Lease lease = RelayRequestScope.createLease();
             RelayRequestScope scope = RelayRequestScope.enter(lease.id)) {
            when(actions.requestApproval(anyString(), anyString(), anyString(), any(), anyLong()))
                    .thenAnswer(ignored -> {
                        lease.close(); // network generation ended while waiting for the user
                        return new JSONObject().put("approved", true);
                    });
            assertThrows(RelayRequestScope.CancelledException.class, () -> runtime.execute("workspace.write",
                    new JSONObject().put("path", "test.txt").put("content", "x"), 1L));
        }
        verify(actions, never()).workspaceWriteFile(any(), anyLong());
    }

    @Test public void expiredLeaseReturnsConflictButOrdinaryLocalHttpStillWorks() throws Exception {
        McpToolActions actions = mock(McpToolActions.class, CALLS_REAL_METHODS);
        when(actions.phoneStatus(anyLong())).thenReturn(new JSONObject().put("unitTest", true));
        start(actions);
        RelayRequestScope.Lease lease = RelayRequestScope.createLease();
        lease.close();
        assertEquals(409, http(lease.id));
        verify(actions, never()).phoneStatus(anyLong());
        assertEquals(200, http(null));
        verify(actions, times(1)).phoneStatus(anyLong());
    }

    @Test public void requestCancelledInsideHttpWorkerQueueNeverCallsTheAction() throws Exception {
        CountDownLatch workersOccupied = new CountDownLatch(4);
        CountDownLatch releaseWorkers = new CountDownLatch(1);
        AtomicInteger executed = new AtomicInteger();
        McpToolActions actions = mock(McpToolActions.class, CALLS_REAL_METHODS);
        when(actions.phoneStatus(anyLong())).thenAnswer(ignored -> {
            executed.incrementAndGet();
            workersOccupied.countDown();
            if (!releaseWorkers.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test wait timeout");
            return new JSONObject().put("unitTest", true);
        });
        start(actions);
        ExecutorService clients = Executors.newFixedThreadPool(5);
        try (RelayRequestScope.Lease lease = RelayRequestScope.createLease()) {
            List<Future<Integer>> ordinaryRequests = new ArrayList<>();
            for (int i = 0; i < 4; i++) ordinaryRequests.add(clients.submit(() -> http(null)));
            assertTrue(workersOccupied.await(3, TimeUnit.SECONDS));
            Future<Integer> oldRelayRequest = clients.submit(() -> http(lease.id));
            Field field = McpHttpServer.class.getDeclaredField("workers");
            field.setAccessible(true);
            ThreadPoolExecutor workers = (ThreadPoolExecutor) field.get(server);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (workers.getQueue().isEmpty() && System.nanoTime() < deadline) Thread.sleep(5L);
            assertFalse("Fifth request must be waiting in the local HTTP queue", workers.getQueue().isEmpty());
            lease.close();
            releaseWorkers.countDown();
            for (Future<Integer> request : ordinaryRequests) assertEquals(200, request.get(3, TimeUnit.SECONDS).intValue());
            assertEquals(409, oldRelayRequest.get(3, TimeUnit.SECONDS).intValue());
            assertEquals(4, executed.get());
        } finally {
            releaseWorkers.countDown();
            clients.shutdownNow();
        }
    }

    private void start(McpToolActions actions) throws Exception {
        try (ServerSocket reservation = new ServerSocket(0)) { port = reservation.getLocalPort(); }
        server = new McpHttpServer(port, "unit-test-token", actions);
        server.start();
    }

    private int http(String lease) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/mcp").openConnection();
        try {
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(5000);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Authorization", "Bearer unit-test-token");
            connection.setRequestProperty("Content-Type", "application/json");
            if (lease != null) connection.setRequestProperty(RelayRequestScope.HEADER, lease);
            byte[] body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"phone_status\",\"arguments\":{\"agent\":\"Test Model 1.0\"}}}"
                    .getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = connection.getOutputStream()) { output.write(body); }
            return connection.getResponseCode();
        } finally {
            connection.disconnect();
        }
    }
}
