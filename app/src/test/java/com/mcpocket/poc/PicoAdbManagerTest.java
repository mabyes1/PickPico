package com.mcpocket.poc;

import android.content.Context;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.util.ReflectionHelpers;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutorService;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public final class PicoAdbManagerTest {
    private final String serial = "127.0.0.1:39991";
    private Context context;
    private PicoAdbClient client;
    private PicoAdbDiscovery discovery;
    private PicoAdbManager manager;

    @Before public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        android.provider.Settings.Global.putInt(context.getContentResolver(), DeveloperOptionsControl.WIFI_ADB, 1);
        context.getSharedPreferences("picoadb_state", 0).edit().clear()
                .putBoolean("paired", true).putString("last_pair_at", "original-pair")
                .putString("last_connect_at", "historic-connect").putString("last_serial", serial).commit();
        client = mock(PicoAdbClient.class);
        discovery = mock(PicoAdbDiscovery.class);
        when(client.status()).thenReturn(new JSONObject().put("enginePresent", true));
        when(discovery.status()).thenReturn(new JSONObject());
        devices("device");
        when(client.runInWorkspace(anyList(), anyLong(), any(File.class), any(File.class))).thenAnswer(call -> {
            File output = call.getArgument(3);
            Files.write(output.toPath(), "uid=2000(shell)\n".getBytes(StandardCharsets.UTF_8));
            return new PicoAdbClient.Result(0, false, false, "");
        });
        manager = new PicoAdbManager(context, client, discovery);
    }

    @After public void cleanup() {
        if (manager != null) {
            ExecutorService worker = ReflectionHelpers.getField(manager, "worker");
            worker.shutdownNow();
        }
    }

    private void devices(String state) {
        PicoAdbClient.Result result = new PicoAdbClient.Result(0, false, false,
                "List of devices attached\n" + serial + "\t" + state + " product:test\n");
        when(client.devices()).thenReturn(result);
        when(client.probeDevices()).thenReturn(result);
    }

    @Test public void autoReconnectOffAllowsExistingTransportButNeverDiscoversOrConnects() throws Exception {
        manager.setAutoReconnectEnabled(false);
        assertFalse(manager.status().getBoolean("autoReconnectEnabled"));
        clearInvocations(discovery);
        assertTrue(manager.execute(new JSONObject().put("command", "id"), true, 1).getBoolean("success"));
        devices("offline");
        JSONObject result = manager.execute(new JSONObject().put("command", "id"), true, 1);
        assertTrue(result.getJSONObject("error").getString("message").contains("ADB_AUTO_RECONNECT_DISABLED"));
        verify(discovery, never()).connectEndpoint();
        verify(client, never()).connect(anyString(), anyInt());
        assertFalse(context.getSharedPreferences("picoadb_state", 0).getBoolean("auto_reconnect", true));
    }

    @Test public void manuallyDisabledAdbNeverReconnectsEvenWhenAutomaticReconnectionIsOn() throws Exception {
        devices("device"); // Native state can lag behind the user's OFF switch.
        android.provider.Settings.Global.putInt(context.getContentResolver(), DeveloperOptionsControl.WIFI_ADB, 0);
        assertTrue(manager.isAutoReconnectEnabled());
        JSONObject result = manager.execute(new JSONObject().put("command", "id"), true, 1);
        assertTrue(result.getJSONObject("error").getString("message").contains("ADB_DISABLED"));
        verify(discovery, never()).connectEndpoint();
        verify(client, never()).connect(anyString(), anyInt());
        verify(client, never()).runInWorkspace(anyList(), anyLong(), any(File.class), any(File.class));
    }

    @Test public void savedPairingAndHistoricConnectDoNotMeanConnected() throws Exception {
        devices("offline");
        manager.refreshConnectionNow();
        JSONObject status = manager.status();
        assertTrue(status.getBoolean("paired"));
        assertFalse(status.getBoolean("connected"));
        assertEquals("offline", status.getJSONObject("connection").getString("state"));
        assertEquals("original-pair", status.getString("lastPairAt"));
        assertEquals("historic-connect", status.getString("lastConnectAt"));
    }

    @Test public void connectionChangesWithoutErasingPairingAndOldChecksExpire() throws Exception {
        manager.refreshConnectionNow();
        assertTrue(manager.status().getBoolean("connected"));
        ShadowSystemClock.advanceBy(Duration.ofSeconds(11));
        assertFalse(manager.status().getBoolean("connected"));
        assertEquals("unknown", manager.status().getJSONObject("connection").getString("state"));
        devices("offline"); manager.refreshConnectionNow();
        assertFalse(manager.status().getBoolean("connected"));
        assertTrue(manager.status().getBoolean("paired"));
    }

    @Test public void shellUsesRemoteShellAndCapturesCompleteOutput() throws Exception {
        String command = "id; printf '%s' 'a b' | wc -c";
        JSONObject result = manager.execute(new JSONObject().put("command", command), true, 1);
        assertTrue(result.toString(), result.getBoolean("success"));
        assertEquals("uid=2000(shell)\n", result.getString("output"));
        assertFalse(result.getBoolean("outputTruncated"));
        verify(client).runInWorkspace(eq(Arrays.asList("-s", serial, "shell", "-T", "-n", command)),
                eq(30000L), eq(new File(context.getFilesDir(), "workspaces")), any(File.class));
        verify(discovery, never()).start();
    }

    @Test public void pushPullAndInstallReachTheNativeClientAsArgumentVectors() throws Exception {
        for (List<String> args : Arrays.asList(
                Arrays.asList("push", "file with spaces.txt", "/sdcard/Download/file with spaces.txt"),
                Arrays.asList("pull", "/sdcard/Download/file with spaces.txt", "copy.txt"),
                Arrays.asList("install", "-r", "app.apk"))) {
            JSONObject result = manager.execute(new JSONObject().put("args", new JSONArray(args)), false, 1);
            assertTrue(result.toString(), result.getBoolean("success"));
            List<String> expected = new ArrayList<>(Arrays.asList("-s", serial)); expected.addAll(args);
            verify(client).runInWorkspace(eq(expected), eq(30000L), any(File.class), any(File.class));
        }
    }

    @Test public void offlineTransportCannotDispatchACommandAsConnected() throws Exception {
        devices("offline");
        when(discovery.connectEndpoint()).thenReturn(new PicoAdbDiscovery.Endpoint("phone", "127.0.0.1", 39992));
        when(client.connect(anyString(), anyInt())).thenReturn(PicoAdbClient.Result.failure(1, false, "connection refused"));
        JSONObject result = manager.execute(new JSONObject().put("command", "id"), true, 1);
        assertTrue(result.getBoolean("isError"));
        assertEquals("picoadb_not_connected", result.getJSONObject("error").getString("code"));
        verify(client, never()).runInWorkspace(anyList(), anyLong(), any(File.class), any(File.class));
        assertTrue(manager.status().getBoolean("paired"));
    }

    @Test public void expiredAuthorizationIsExposedWithoutErasingIdentityAndClearedOnRecovery() throws Exception {
        devices("offline");
        when(discovery.connectEndpoint()).thenReturn(new PicoAdbDiscovery.Endpoint("phone", "127.0.0.1", 39991));
        when(client.connect(anyString(), anyInt())).thenReturn(PicoAdbClient.Result.failure(1, false,
                "ADB_AUTHORIZATION_REQUIRED: Android rejected the saved ADB identity"));
        JSONObject result = manager.execute(new JSONObject().put("command", "id"), true, 1);
        assertTrue(result.getJSONObject("error").getString("message").contains("ADB_AUTHORIZATION_REQUIRED"));
        assertTrue(manager.status().getBoolean("authorizationRequired"));
        assertTrue(manager.status().getBoolean("paired"));
        assertEquals("original-pair", manager.status().getString("lastPairAt"));
        // A fresh manager must retain the recovery requirement after an app restart.
        PicoAdbManager restored = new PicoAdbManager(context, client, discovery);
        try {
            assertTrue(restored.status().getBoolean("authorizationRequired"));
            when(client.connect(anyString(), anyInt())).thenAnswer(call -> {
                devices("device");
                return new PicoAdbClient.Result(0, false, false, "connected to " + serial);
            });
            assertTrue(restored.execute(new JSONObject().put("command", "id"), true, 1).getBoolean("success"));
            assertFalse(restored.status().getBoolean("authorizationRequired"));
        } finally { ((ExecutorService) ReflectionHelpers.getField(restored, "worker")).shutdownNow(); }
    }

    @Test public void missingNativeInstallPushesApkUsesPackageManagerAndCleansOnlyItsStaging() throws Exception {
        File workspace = new File(context.getFilesDir(), "workspaces");
        workspace.mkdirs();
        File apk = new File(workspace, "a 'quoted'; app.apk");
        Files.write(apk.toPath(), new byte[]{1, 2, 3});
        List<List<String>> calls = new ArrayList<>();
        when(client.runInWorkspace(anyList(), anyLong(), any(File.class), any(File.class))).thenAnswer(call -> {
            List<String> args = call.getArgument(0); calls.add(args);
            File output = call.getArgument(3);
            boolean missing = "install".equals(args.get(2));
            Files.write(output.toPath(), (missing ? "libadb.so: unknown command install\n" : "Success\n").getBytes(StandardCharsets.UTF_8));
            return new PicoAdbClient.Result(missing ? 1 : 0, false, false, "");
        });
        JSONObject result = manager.execute(new JSONObject().put("args", new JSONArray()
                .put("install").put("-r").put("--user").put("0").put(apk.getName())), false, 1);
        assertTrue(result.toString(), result.getBoolean("success"));
        assertFalse(result.getString("output").contains("unknown command"));
        assertEquals(5, calls.size());
        List<String> push = calls.get(2);
        assertEquals(Arrays.asList("-s", serial, "push", apk.getAbsolutePath()), push.subList(0, 4));
        String staged = push.get(4);
        assertTrue(staged.startsWith("/data/local/tmp/picoadb-install-"));
        assertTrue(staged.endsWith("/base.apk"));
        assertEquals("'cmd' 'package' 'install' '-r' '--user' '0' '" + staged + "'", calls.get(3).get(5));
        assertEquals("rm -rf -- '" + staged.substring(0, staged.lastIndexOf('/')) + "'", calls.get(4).get(5));
        assertTrue(apk.isFile());
    }

    @Test public void failedPushAbortsInstallationButStillCleansItsStaging() throws Exception {
        File workspace = new File(context.getFilesDir(), "workspaces"); workspace.mkdirs();
        Files.write(new File(workspace, "app.apk").toPath(), new byte[]{1});
        List<List<String>> calls = new ArrayList<>();
        when(client.runInWorkspace(anyList(), anyLong(), any(File.class), any(File.class))).thenAnswer(call -> {
            List<String> args = call.getArgument(0); calls.add(args);
            boolean missing = "install".equals(args.get(2));
            boolean push = "push".equals(args.get(2));
            Files.write(call.<File>getArgument(3).toPath(), (missing ? "unknown command install\n" : push ? "push denied\n" : "").getBytes(StandardCharsets.UTF_8));
            return new PicoAdbClient.Result(missing || push ? 1 : 0, false, false, "");
        });
        JSONObject result = manager.execute(new JSONObject().put("args", new JSONArray().put("install").put("app.apk")), false, 1);
        assertFalse(result.getBoolean("success"));
        assertTrue(result.getString("output").contains("push denied"));
        assertEquals(4, calls.size());
        assertTrue(calls.get(3).get(5).startsWith("rm -rf -- '/data/local/tmp/picoadb-install-"));
        assertFalse(calls.stream().anyMatch(args -> args.get(args.size() - 1).startsWith("'cmd' 'package' 'install'")));
    }

    @Test public void missingNativeUninstallUsesQuotedPackageManagerArguments() throws Exception {
        List<List<String>> calls = new ArrayList<>();
        when(client.runInWorkspace(anyList(), anyLong(), any(File.class), any(File.class))).thenAnswer(call -> {
            List<String> args = call.getArgument(0); calls.add(args);
            boolean missing = "uninstall".equals(args.get(2));
            Files.write(call.<File>getArgument(3).toPath(), (missing ? "unknown command uninstall\n" : "Success\n").getBytes(StandardCharsets.UTF_8));
            return new PicoAdbClient.Result(missing ? 1 : 0, false, false, "");
        });
        JSONObject result = manager.execute(new JSONObject().put("args", new JSONArray()
                .put("uninstall").put("-k").put("a'b;echo injected")), false, 1);
        assertTrue(result.getBoolean("success"));
        assertEquals(2, calls.size());
        assertEquals("cmd package 'uninstall' '-k' 'a'\\''b;echo injected'", calls.get(1).get(5));
    }

    @Test public void inlineOutputIsBoundedButWorkspaceFileRemainsComplete() throws Exception {
        when(client.runInWorkspace(anyList(), anyLong(), any(File.class), any(File.class))).thenAnswer(call -> {
            byte[] output = new byte[5000]; Arrays.fill(output, (byte) 'x');
            Files.write(call.<File>getArgument(3).toPath(), output);
            return new PicoAdbClient.Result(7, false, false, "");
        });
        JSONObject result = manager.execute(new JSONObject().put("command", "exit 7").put("maxOutputBytes", 1024), true, 1);
        assertTrue(result.getBoolean("isError"));
        assertEquals(7, result.getInt("exitCode"));
        assertEquals(1024, result.getString("output").length());
        assertTrue(result.getBoolean("outputTruncated"));
        assertEquals(5000, result.getLong("bytes"));
        assertEquals(5000, new File(context.getFilesDir(), "workspaces/" + result.getString("workspacePath")).length());
    }
}
