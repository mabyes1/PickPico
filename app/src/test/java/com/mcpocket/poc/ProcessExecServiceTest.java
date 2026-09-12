package com.mcpocket.poc;

import android.os.SystemClock;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public final class ProcessExecServiceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test(timeout = 10000) public void foregroundServiceTimesOutWhileChildNeverReads() throws Exception {
        runScenario(false, false);
    }

    @Test(timeout = 10000) public void backgroundSessionCanBeQueriedAndStoppedDuringBlockedInput() throws Exception {
        runScenario(true, true);
    }

    @Test(timeout = 10000) public void backgroundInputDeadlineIsVisibleInSessionStatus() throws Exception {
        runScenario(true, false);
    }

    private void runScenario(boolean background, boolean stopManually) throws Exception {
        McpNodeService service = mock(McpNodeService.class, CALLS_REAL_METHODS);
        doNothing().when(service).recordExec(anyString(), anyLong(), anyString());
        doReturn(temporary.getRoot()).when(service).getCacheDir();
        Field sessions = McpNodeService.class.getDeclaredField("processSessions");
        sessions.setAccessible(true); sessions.set(service, new LinkedHashMap<>());
        CountDownLatch writing = new CountDownLatch(1), killed = new CountDownLatch(1);
        Process process = mock(Process.class);
        when(process.isAlive()).thenAnswer(i -> killed.getCount() > 0);
        when(process.exitValue()).thenAnswer(i -> {
            if (killed.getCount() > 0) throw new IllegalThreadStateException();
            return 137;
        });
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(process.getErrorStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(process.getOutputStream()).thenReturn(new OutputStream() {
            @Override public void write(int value) throws IOException {
                writing.countDown();
                try { killed.await(); } catch (InterruptedException e) { throw new IOException(e); }
            }
            @Override public void write(byte[] value, int offset, int count) throws IOException { write(0); }
        });
        when(process.destroyForcibly()).thenAnswer(i -> { killed.countDown(); return process; });
        doAnswer(i -> { killed.countDown(); return null; }).when(process).destroy();
        when(process.waitFor(anyLong(), any(TimeUnit.class))).thenAnswer(i ->
                killed.await(i.getArgument(0), i.getArgument(1)));
        try (MockedStatic<SystemClock> clock = mockStatic(SystemClock.class);
             MockedConstruction<ProcessBuilder> builders = mockConstruction(ProcessBuilder.class, (builder, context) -> {
                 when(builder.start()).thenAnswer(invocation -> {
                     File[] files = temporary.getRoot().listFiles((directory, name) -> name.endsWith(".pid"));
                     assertNotNull(files); assertEquals(1, files.length);
                     // The process itself is a mock. No shell or Android signal is launched.
                     Files.write(files[0].toPath(), "123456".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                     return process;
                 });
             })) {
            clock.when(SystemClock::elapsedRealtime).thenAnswer(i -> System.nanoTime() / 1_000_000L);
            int timeout = stopManually ? 3000 : 300;
            JSONObject arguments = new JSONObject().put("command", "unit-test-no-process")
                    .put("cwd", temporary.getRoot().getAbsolutePath()).put("stdin", "x".repeat(65536))
                    .put("timeoutMs", timeout).put("background", background);
            JSONObject result = service.execCommand(arguments, 1L);
            assertTrue(writing.await(1, TimeUnit.SECONDS));
            if (!background) {
                assertTrue(result.getBoolean("timedOut"));
                assertEquals("timed_out", result.getString("stdinState"));
                assertFalse(process.isAlive());
                return;
            }
            String sessionId = result.getString("sessionId");
            JSONObject session = new JSONObject().put("sessionId", sessionId).put("force", true);
            if (stopManually) {
                assertTrue(result.getBoolean("running"));
                assertEquals("writing", result.getString("stdinState"));
                JSONObject stopped = service.killProcessSession(session, 2L);
                assertFalse(stopped.getBoolean("running"));
                assertEquals("cancelled", stopped.getString("stdinState"));
            } else {
                assertTrue(killed.await(2, TimeUnit.SECONDS));
                JSONObject status = service.readProcessOutput(session, 2L);
                assertTrue(status.getBoolean("timedOut"));
                assertEquals("timed_out", status.getString("stdinState"));
                assertFalse(status.getBoolean("running"));
            }
        } finally { killed.countDown(); }
    }
}
