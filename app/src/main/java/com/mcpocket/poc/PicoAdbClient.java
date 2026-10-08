package com.mcpocket.poc;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
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
 * Raw execution is package-private so MCP capabilities can expose only audited
 * operations instead of turning PicoADB into a remote arbitrary shell.
 */
final class PicoAdbClient {
    private static final long PAIR_TIMEOUT_MS = 30_000L;
    private static final long CONNECT_TIMEOUT_MS = 20_000L;
    // dumpsys batterystats can easily exceed a chat-sized payload. Keep enough
    // data to write a complete workspace artifact; MCP returns only metadata.
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
        return run(list("connect", endpoint(host, port)), CONNECT_TIMEOUT_MS);
    }

    Result devices() {
        return run(list("devices", "-l"), CONNECT_TIMEOUT_MS);
    }

    /** Execute one already-audited adb argument vector. */
    Result runChecked(List<String> arguments, long timeoutMs) {
        if (arguments == null || arguments.isEmpty()) {
            return Result.validation("ADB arguments are empty");
        }
        if (timeoutMs < 100L || timeoutMs > 120_000L) {
            return Result.validation("ADB timeout must be between 100 and 120000 ms");
        }
        return run(new ArrayList<>(arguments), timeoutMs);
    }

    private Result run(List<String> arguments, long timeoutMs) {
        if (!adbBinary.isFile()) {
            return Result.failure(-1, false, "PicoADB engine is unavailable");
        }
        ensureHome();

        List<String> command = new ArrayList<>(arguments.size() + 1);
        command.add(adbBinary.getAbsolutePath());
        command.addAll(arguments);

        Process process = null;
        StreamCollector collector = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(adbHome)
                    .redirectErrorStream(true);
            builder.environment().put("HOME", adbHome.getAbsolutePath());
            builder.environment().put("TMPDIR", context.getCacheDir().getAbsolutePath());
            builder.environment().put("ANDROID_SDK_HOME", adbHome.getAbsolutePath());

            process = builder.start();
            collector = new StreamCollector(process.getInputStream(), MAX_OUTPUT_BYTES);
            collector.start();

            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroy();
                if (!process.waitFor(750L, TimeUnit.MILLISECONDS)) process.destroyForcibly();
            }
            collector.join(2_000L);
            String output = collector.output();
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

