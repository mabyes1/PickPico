package com.mcpocket.poc;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Process wrapper around the pinned Android adb client engine.
 *
 * The engine owns the wire protocol and cryptography. This class owns process
 * isolation, persistent key location, bounded execution and safe result data.
 * MCP execution uses the same persistent identity and bounded process runner.
 */
final class PicoAdbClient {
    private static final Object DAEMON_POLICY_LOCK = new Object();
    private static final long PAIR_TIMEOUT_MS = 30_000L;
    private static final long CONNECT_TIMEOUT_MS = 20_000L;
    // Interactive command output is bounded in memory. Diagnostics stream to disk.
    private static final int MAX_OUTPUT_BYTES = 4 * 1024 * 1024;

    private final Context context;
    private final File adbBinary;
    private final File adbHome;

    PicoAdbClient(Context context) {
        this.context = context.getApplicationContext();
        this.adbBinary = new File(this.context.getApplicationInfo().nativeLibraryDir, "libadb.so");
        this.adbHome = new File(this.context.getFilesDir(), "picoadb");
    }

    JSONObject status() throws JSONException {
        File androidHome = new File(adbHome, ".android");
        File privateKey = new File(androidHome, "adbkey");
        return new JSONObject()
                .put("enginePath", adbBinary.getAbsolutePath())
                .put("enginePresent", adbBinary.isFile())
                .put("engineExecutable", adbBinary.canExecute())
                .put("home", adbHome.getAbsolutePath())
                .put("identityCreated", privateKey.isFile())
                .put("pairedIdentityPersistent", true);
    }

    Result pair(String host, int port, String pairingCode) {
        if (!isValidHost(host)) return Result.validation("Pairing host is invalid");
        if (!isValidPort(port)) return Result.validation("Pairing port is invalid");
        if (!isValidPairingCode(pairingCode)) {
            return Result.validation("Pairing code must contain exactly 6 digits");
        }
        return run(
                list("pair", endpoint(host, port), pairingCode),
                PAIR_TIMEOUT_MS);
    }

    Result connect(String host, int port) {
        if (!isValidHost(host)) return Result.validation("Connect host is invalid");
        if (!isValidPort(port)) return Result.validation("Connect port is invalid");
        File log = new File(context.getCacheDir(), "adb." + android.os.Process.myUid() + ".log");
        long offset = log.length();
        Result result = run(list("connect", endpoint(host, port)), CONNECT_TIMEOUT_MS);
        return diagnoseConnect(result, log, offset, endpoint(host, port));
    }

    // The native client hides TLS rejection behind "failed to connect" (even exit 0).
    // Inspect only this attempt's bounded log tail, never historical failures.
    static Result diagnoseConnect(Result result, File log, long offset, String endpoint) {
        if (result.connected()) return result;
        try (RandomAccessFile input = new RandomAccessFile(log, "r")) {
            long start = input.length() < offset ? 0 : offset;
            input.seek(Math.max(start, input.length() - 16384));
            byte[] bytes = new byte[(int) Math.min(16384, input.length() - input.getFilePointer())];
            input.readFully(bytes);
            String appended = new String(bytes, StandardCharsets.UTF_8);
            if (appended.contains("SSLV3_ALERT_CERTIFICATE_UNKNOWN") && appended.contains(endpoint))
                return Result.failure(1, false, "ADB_AUTHORIZATION_REQUIRED: Android rejected the saved ADB identity. "
                        + "The authorization may have expired or been revoked; pair again using Android Wireless Debugging. "
                        + "The existing key is retained.");
        } catch (java.io.IOException ignored) {
            // Missing diagnostics must not obscure the original connection failure.
        }
        return result;
    }

    Result devices() {
        return run(list("devices", "-l"), CONNECT_TIMEOUT_MS);
    }

    Result probeDevices() {
        // Starting the daemon after an app update takes over 3 seconds on the S23.
        // This runs off the UI thread; allow cold startup to finish before checking.
        return run(list("devices", "-l"), 10_000L);
    }

    /** Match the exact transport row; the header also contains the word devices. */
    static String transportState(String output, String serial) {
        if (serial == null || serial.isEmpty()) return "disconnected";
        for (String line : (output == null ? "" : output).split("\\r?\\n")) {
            String[] columns = line.trim().split("\\s+");
            if (columns.length < 2 || !serial.equals(columns[0])) continue;
            switch (columns[1]) {
                case "device": return "connected";
                case "offline": return "offline";
                case "unauthorized": return "unauthorized";
                default: return "disconnected";
            }
        }
        return "disconnected";
    }

    Result runInWorkspace(List<String> arguments, long timeoutMs, File workspace, File outputFile) {
        return run(new ArrayList<>(arguments), timeoutMs, outputFile, workspace);
    }

    /** Execute one already-audited adb argument vector. */
    Result runChecked(List<String> arguments, long timeoutMs) {
        return runChecked(arguments, timeoutMs, null);
    }

    Result runChecked(List<String> arguments, long timeoutMs, File outputFile) {
        if (arguments == null || arguments.isEmpty()) {
            return Result.validation("ADB arguments are empty");
        }
        if (timeoutMs < 100L || timeoutMs > 120_000L) {
            return Result.validation("ADB timeout must be between 100 and 120000 ms");
        }
        return run(new ArrayList<>(arguments), timeoutMs, outputFile);
    }

    private Result run(List<String> arguments, long timeoutMs) {
        return run(arguments, timeoutMs, null);
    }

    private Result run(List<String> arguments, long timeoutMs, File outputFile) {
        return run(arguments, timeoutMs, outputFile, adbHome);
    }

    private Result run(List<String> arguments, long timeoutMs, File outputFile, File cwd) {
        if (!adbBinary.isFile()) {
            return Result.failure(-1, false, "PicoADB engine is unavailable");
        }
        ensureHome();

        List<String> command = new ArrayList<>(arguments.size() + 1);
        command.add(adbBinary.getAbsolutePath());
        command.addAll(arguments);

        try {
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(cwd)
                    .redirectErrorStream(true);
            builder.environment().put("HOME", adbHome.getAbsolutePath());
            builder.environment().put("TMPDIR", context.getCacheDir().getAbsolutePath());
            builder.environment().put("ANDROID_SDK_HOME", adbHome.getAbsolutePath());
            // Java owns reconnection policy; native mDNS must not bypass the UI switch.
            builder.environment().put("ADB_MDNS_AUTO_CONNECT", "0");

            synchronized (DAEMON_POLICY_LOCK) {
                android.content.SharedPreferences policy = context.getSharedPreferences("picoadb_state", 0);
                if (!policy.getBoolean("native_autoconnect_disabled_v1", false)) {
                    // An old orphan daemon keeps its original environment across APK updates.
                    // Replace it once so the user's switch also governs upgraded installs.
                    ProcessBuilder stop = new ProcessBuilder(adbBinary.getAbsolutePath(), "kill-server")
                            .directory(cwd).redirectErrorStream(true);
                    stop.environment().putAll(builder.environment());
                    Result stopped = execute(stop, 5000L, null);
                    if (!stopped.success()) return stopped;
                    policy.edit().putBoolean("native_autoconnect_disabled_v1", true).apply();
                }
            }

            return execute(builder, timeoutMs, outputFile);
        } catch (Exception error) {
            return Result.failure(-1, false,
                    error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage()));
        }
    }

    static Result execute(ProcessBuilder builder, long timeoutMs, File outputFile) {
        Process process = null;
        StreamCollector collector = null;
        try {
            builder.redirectErrorStream(true);
            if (outputFile != null) builder.redirectOutput(outputFile);
            process = builder.start();
            process.getOutputStream().close(); // Non-interactive MCP calls never wait for host stdin.
            if (outputFile == null) {
                collector = new StreamCollector(process.getInputStream(), MAX_OUTPUT_BYTES);
                collector.start();
            }

            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroy();
                if (!process.waitFor(750L, TimeUnit.MILLISECONDS)) process.destroyForcibly();
            }
            if (collector != null) collector.join(2_000L);
            String output = collector == null ? "" : collector.output();
            // Keep only a small error excerpt in the response, never the full dump.
            if (outputFile != null && (!finished || process.exitValue() != 0)) {
                try (InputStream input = new FileInputStream(outputFile)) {
                    byte[] excerpt = new byte[2_000];
                    int read = input.read(excerpt);
                    output = read < 0 ? "" : new String(excerpt, 0, read, StandardCharsets.UTF_8);
                }
            }
            if (!finished) return Result.failure(-1, true, output);
            return new Result(process.exitValue(), false, false, output);
        } catch (Exception error) {
            if (process != null) process.destroyForcibly();
            String detail = error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
            return Result.failure(-1, false, detail);
        }
    }

    private void ensureHome() {
        File androidHome = new File(adbHome, ".android");
        if (!androidHome.isDirectory() && !androidHome.mkdirs() && !androidHome.isDirectory()) {
            throw new IllegalStateException("Unable to create PicoADB home");
        }
    }

    static boolean isValidPairingCode(String value) {
        return value != null && value.matches("[0-9]{6}");
    }

    static boolean isValidPort(int port) {
        return port > 0 && port <= 65_535;
    }

    static boolean isValidHost(String host) {
        if (host == null) return false;
        String value = host.trim();
        if (value.isEmpty() || value.length() > 255) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == ':' || c == '%') continue;
            return false;
        }
        return true;
    }

    static String endpoint(String host, int port) {
        String value = host.trim();
        if (value.indexOf(':') >= 0 && !value.startsWith("[")) value = "[" + value + "]";
        return value + ":" + port;
    }

    private static List<String> list(String... values) {
        List<String> result = new ArrayList<>();
        Collections.addAll(result, values);
        return result;
    }

    static final class Result {
        final int exitCode;
        final boolean timedOut;
        final boolean validationError;
        final String output;

        Result(int exitCode, boolean timedOut, boolean validationError, String output) {
            this.exitCode = exitCode;
            this.timedOut = timedOut;
            this.validationError = validationError;
            this.output = output == null ? "" : output.trim();
        }

        boolean success() {
            return !validationError && !timedOut && exitCode == 0;
        }

        boolean paired() {
            String normalized = output.toLowerCase(Locale.ROOT);
            return success() && normalized.contains("successfully paired");
        }

        boolean connected() {
            String normalized = output.toLowerCase(Locale.ROOT);
            return success() && (normalized.contains("connected to")
                    || normalized.contains("already connected"));
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject()
                    .put("success", success())
                    .put("exitCode", exitCode)
                    .put("timedOut", timedOut)
                    .put("validationError", validationError)
                    .put("output", output);
        }

        static Result validation(String message) {
            return new Result(-1, false, true, message);
        }

        static Result failure(int exitCode, boolean timedOut, String message) {
            return new Result(exitCode, timedOut, false, message);
        }
    }

    private static final class StreamCollector extends Thread {
        private final InputStream input;
        private final int limit;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        StreamCollector(InputStream input, int limit) {
            super("picoadb-output");
            setDaemon(true);
            this.input = input;
            this.limit = limit;
        }

        @Override public void run() {
            byte[] buffer = new byte[2048];
            try (InputStream closeable = input) {
                int read;
                while ((read = closeable.read(buffer)) >= 0) {
                    int remaining = limit - output.size();
                    if (remaining > 0) output.write(buffer, 0, Math.min(read, remaining));
                }
            } catch (Exception ignored) {
            }
        }

        String output() {
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}

