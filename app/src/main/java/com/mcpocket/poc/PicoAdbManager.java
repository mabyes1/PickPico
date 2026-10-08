package com.mcpocket.poc;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * User-owned Wireless Debugging setup and bounded privileged execution.
 *
 * Discovery is deliberately on-demand. Leaving NSD plus a multicast lock alive
 * for the lifetime of the foreground node would undermine Doze and make the
 * very battery diagnostic feature a new source of battery drain.
 */
final class PicoAdbManager {
    interface Callback {
        void onResult(JSONObject result);
    }

    private static final String PREFS = "picoadb_state";
    private static final String KEY_PAIRED = "paired";
    private static final String KEY_LAST_PAIR_AT = "last_pair_at";
    private static final String KEY_LAST_CONNECT_AT = "last_connect_at";
    private static final String KEY_LAST_SERIAL = "last_serial";
    private static final long DEFAULT_DISCOVERY_MS = 15_000L;
    private static final long MIN_DISCOVERY_MS = 5_000L;
    private static final long MAX_DISCOVERY_MS = 60_000L;
    private static final long ENDPOINT_WAIT_MS = 15_000L;

    private static volatile PicoAdbManager instance;

    static PicoAdbManager get(Context context) {
        PicoAdbManager value = instance;
        if (value != null) return value;
        synchronized (PicoAdbManager.class) {
            value = instance;
            if (value == null) {
                value = new PicoAdbManager(context.getApplicationContext());
                instance = value;
            }
            return value;
        }
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final PicoAdbDiscovery discovery;
    private final PicoAdbClient client;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "picoadb-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final Runnable stopDiscoveryRunnable = this::stopDiscoveryOnMain;

    private boolean pairing;
    private boolean connecting;
    private boolean diagnosing;
    private long discoveryDeadlineElapsed;
    private String lastAction = "";
    private String lastError = "";
    private String lastOutput = "";

    private PicoAdbManager(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.discovery = new PicoAdbDiscovery(context);
        this.client = new PicoAdbClient(context);
    }

    void discover() {
        discover(DEFAULT_DISCOVERY_MS);
    }

    void discover(long durationMs) {
        long bounded = Math.max(MIN_DISCOVERY_MS, Math.min(MAX_DISCOVERY_MS, durationMs));
        mainHandler.post(() -> startDiscoveryOnMain(bounded));
    }

    void stopDiscovery() {
        mainHandler.post(this::stopDiscoveryOnMain);
    }

    synchronized JSONObject status() throws JSONException {
        JSONObject engine = client.status();
        JSONObject scan = discovery.status();
        boolean paired = prefs.getBoolean(KEY_PAIRED, false);
        String state;
        if (!engine.optBoolean("enginePresent", false)) state = "engine_unavailable";
        else if (diagnosing) state = "diagnosing";
        else if (pairing) state = "pairing";
        else if (connecting) state = "connecting";
        else if (discovery.pairingEndpoint() != null && !paired) state = "ready_to_pair";
        else if (discovery.connectEndpoint() != null && paired) state = "ready_to_connect";
        else if (discovery.isStarted()) state = "discovering";
        else if (paired) state = "paired";
        else state = "setup_required";

        return new JSONObject()
                .put("state", state)
                .put("paired", paired)
                .put("lastPairAt", nullablePreference(KEY_LAST_PAIR_AT))
                .put("lastConnectAt", nullablePreference(KEY_LAST_CONNECT_AT))
                .put("lastSerial", nullablePreference(KEY_LAST_SERIAL))
                .put("discoveryDeadlineElapsedMs", discoveryDeadlineElapsed == 0L
                        ? JSONObject.NULL : discoveryDeadlineElapsed)
                .put("engine", engine)
                .put("discovery", scan)
                .put("lastAction", lastAction)
                .put("lastError", lastError)
                .put("lastOutput", lastOutput);
    }

    void pair(String pairingCode, Callback callback) {
        final PicoAdbDiscovery.Endpoint endpoint;
        synchronized (this) {
            if (pairing || connecting || diagnosing) {
                deliver(callback, error("busy", "PicoADB is already handling another operation"));
                return;
            }
            endpoint = discovery.pairingEndpoint();
            if (endpoint == null) {
                discover();
                deliver(callback, error("pairing_endpoint_missing",
                        "Start Android's pairing-code screen, then scan again"));
                return;
            }
            pairing = true;
            lastAction = "pair";
            lastError = "";
            lastOutput = "";
        }

        worker.execute(() -> {
            PicoAdbClient.Result result = client.pair("127.0.0.1", endpoint.port, pairingCode);
            JSONObject payload;
            synchronized (PicoAdbManager.this) {
                pairing = false;
                lastOutput = result.output;
                if (result.paired()) {
                    prefs.edit()
                            .putBoolean(KEY_PAIRED, true)
                            .putString(KEY_LAST_PAIR_AT, Instant.now().toString())
                            .apply();
                    lastError = "";
                } else {
                    lastError = result.output.isEmpty() ? "Pairing failed" : result.output;
                }
                try {
                    payload = result.toJson()
                            .put("paired", result.paired())
                            .put("endpoint", endpoint.toJson());
                } catch (JSONException impossible) {
                    payload = error("serialization_failed", impossible.getMessage());
                }
            }
            if (result.paired()) discover();
            deliver(callback, payload);
        });
    }

    void connect(Callback callback) {
        synchronized (this) {
            if (pairing || connecting || diagnosing) {
                deliver(callback, error("busy", "PicoADB is already handling another operation"));
                return;
            }
            connecting = true;
            lastAction = "connect";
            lastError = "";
            lastOutput = "";
        }

        worker.execute(() -> {
            PicoAdbDiscovery.Endpoint endpoint = awaitEndpoint(false, ENDPOINT_WAIT_MS);
            JSONObject payload;
            if (endpoint == null) {
                synchronized (PicoAdbManager.this) {
                    connecting = false;
                    lastError = "Wireless Debugging connect endpoint was not discovered";
                }
                deliver(callback, error("connect_endpoint_missing",
                        "Enable Wireless Debugging and scan again"));
                return;
            }

            PicoAdbClient.Result result = client.connect(endpoint.host, endpoint.port);
            synchronized (PicoAdbManager.this) {
                connecting = false;
                lastOutput = result.output;
                if (result.connected()) {
                    String serial = PicoAdbClient.endpoint(endpoint.host, endpoint.port);
                    prefs.edit()
                            .putString(KEY_LAST_CONNECT_AT, Instant.now().toString())
                            .putString(KEY_LAST_SERIAL, serial)
                            .apply();
                    lastError = "";
                } else {
                    lastError = result.output.isEmpty() ? "Connection failed" : result.output;
                }
                try {
                    payload = result.toJson()
                            .put("connected", result.connected())
                            .put("endpoint", endpoint.toJson());
                } catch (JSONException impossible) {
                    payload = error("serialization_failed", impossible.getMessage());
                }
            }
            deliver(callback, payload);
        });
    }

    /**
     * Synchronous MCP worker entry for a fixed diagnostic bundle. The caller
     * never supplies shell text. Full outputs are written into the PickPico
     * workspace and can be paged with workspace.read.
     */
    JSONObject batteryDiagnostics(long callCount) throws JSONException {
        synchronized (this) {
            if (pairing || connecting || diagnosing) {
                return error("busy", "PicoADB is already handling another operation")
                        .put("toolCallCount", callCount);
            }
            diagnosing = true;
            lastAction = "battery_diagnostics";
            lastError = "";
            lastOutput = "";
        }

        try {
            String serial = ensureConnected();
            if (serial.isEmpty()) {
                synchronized (this) {
                    lastError = "Unable to connect to this phone's Wireless Debugging endpoint";
                }
                return error("picoadb_not_connected", lastError)
                        .put("toolCallCount", callCount);
            }

            File directory = new File(context.getFilesDir(), "workspaces/diagnostics");
            if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
                throw new IllegalStateException("Unable to create diagnostics workspace");
            }
            String stamp = Instant.now().toString().replace(':', '-');
            JSONObject files = new JSONObject();
            files.put("batterystats", runDiagnostic(serial, directory, stamp,
                    "batterystats", "dumpsys", "batterystats", "--charged"));
            files.put("power", runDiagnostic(serial, directory, stamp,
                    "power", "dumpsys", "power"));
            files.put("deviceidle", runDiagnostic(serial, directory, stamp,
                    "deviceidle", "dumpsys", "deviceidle"));
            files.put("alarm", runDiagnostic(serial, directory, stamp,
                    "alarm", "dumpsys", "alarm"));
            files.put("jobscheduler", runDiagnostic(serial, directory, stamp,
                    "jobscheduler", "dumpsys", "jobscheduler"));

            synchronized (this) {
                lastError = "";
                lastOutput = "Diagnostic bundle written to workspace/diagnostics";
            }
            return new JSONObject()
                    .put("status", "completed")
                    .put("serial", serial)
                    .put("files", files)
                    .put("toolCallCount", callCount);
        } catch (Exception error) {
            synchronized (this) {
                lastError = error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
            }
            return error("diagnostic_failed", lastError).put("toolCallCount", callCount);
        } finally {
            synchronized (this) {
                diagnosing = false;
            }
        }
    }

    private JSONObject runDiagnostic(
            String serial,
            File directory,
            String stamp,
            String name,
            String... remoteArguments) throws Exception {
        String[] command = new String[remoteArguments.length + 3];
        command[0] = "-s";
        command[1] = serial;
        command[2] = "shell";
        System.arraycopy(remoteArguments, 0, command, 3, remoteArguments.length);
        PicoAdbClient.Result result = client.runChecked(Arrays.asList(command), 120_000L);
        File target = new File(directory, stamp + "-" + name + ".txt");
        try (FileOutputStream output = new FileOutputStream(target, false)) {
            output.write(result.output.getBytes(StandardCharsets.UTF_8));
        }
        JSONObject metadata = result.toJson();
        metadata.remove("output");
        metadata.put("workspacePath", "diagnostics/" + target.getName())
                .put("bytes", target.length());
        if (!result.success() && !result.output.isEmpty()) {
            metadata.put("errorOutput", result.output.substring(
                    0, Math.min(2_000, result.output.length())));
        }
        return metadata;
    }

    private String ensureConnected() {
        String existing = prefs.getString(KEY_LAST_SERIAL, "");
        if (!existing.isEmpty()) {
            PicoAdbClient.Result devices = client.devices();
            if (devices.success() && devices.output.contains(existing)
                    && devices.output.contains("device")) return existing;
        }

        PicoAdbDiscovery.Endpoint endpoint = awaitEndpoint(false, ENDPOINT_WAIT_MS);
        if (endpoint == null) return "";
        PicoAdbClient.Result connect = client.connect(endpoint.host, endpoint.port);
        if (!connect.connected()) return "";
        String serial = PicoAdbClient.endpoint(endpoint.host, endpoint.port);
        prefs.edit()
                .putString(KEY_LAST_CONNECT_AT, Instant.now().toString())
                .putString(KEY_LAST_SERIAL, serial)
                .apply();
        return serial;
    }

    private PicoAdbDiscovery.Endpoint awaitEndpoint(boolean pairingEndpoint, long timeoutMs) {
        PicoAdbDiscovery.Endpoint endpoint = pairingEndpoint
                ? discovery.pairingEndpoint() : discovery.connectEndpoint();
        if (endpoint != null) return endpoint;
        discover(Math.max(DEFAULT_DISCOVERY_MS, timeoutMs));
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            endpoint = pairingEndpoint ? discovery.pairingEndpoint() : discovery.connectEndpoint();
            if (endpoint != null) return endpoint;
            try {
                Thread.sleep(200L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private synchronized Object nullablePreference(String key) {
        String value = prefs.getString(key, "");
        return value.isEmpty() ? JSONObject.NULL : value;
    }

    private void startDiscoveryOnMain(long durationMs) {
        synchronized (this) {
            if (!discovery.isStarted()) discovery.start();
            discoveryDeadlineElapsed = SystemClock.elapsedRealtime() + durationMs;
            mainHandler.removeCallbacks(stopDiscoveryRunnable);
            mainHandler.postDelayed(stopDiscoveryRunnable, durationMs);
        }
    }

    private void stopDiscoveryOnMain() {
        synchronized (this) {
            discovery.stop();
            discoveryDeadlineElapsed = 0L;
            mainHandler.removeCallbacks(stopDiscoveryRunnable);
        }
    }

    private void deliver(Callback callback, JSONObject result) {
        if (callback == null) return;
        mainHandler.post(() -> callback.onResult(result));
    }

    private static JSONObject error(String code, String message) {
        try {
            return new JSONObject()
                    .put("success", false)
                    .put("isError", true)
                    .put("error", new JSONObject()
                            .put("code", code)
                            .put("message", message == null ? "" : message));
        } catch (JSONException impossible) {
            return new JSONObject();
        }
    }
}

