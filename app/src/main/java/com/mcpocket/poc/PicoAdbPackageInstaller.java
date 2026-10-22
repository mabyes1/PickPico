package com.mcpocket.poc;

import android.os.SystemClock;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Package operations omitted by the Android-built client, using the same ADB transport. */
final class PicoAdbPackageInstaller {
    private PicoAdbPackageInstaller() { }

    static boolean isMissingPackageCommand(List<String> argv, PicoAdbClient.Result result, File output) throws Exception {
        String command = argv.get(2);
        if (result.success() || !("install".equals(command) || "uninstall".equals(command))) return false;
        try (FileInputStream input = new FileInputStream(output)) {
            byte[] bytes = new byte[512];
            int count = input.read(bytes);
            return count > 0 && new String(bytes, 0, count, java.nio.charset.StandardCharsets.UTF_8)
                    .contains("unknown command " + command);
        }
    }

    static PicoAdbClient.Result run(PicoAdbClient client, List<String> argv, long timeoutMs,
                                   File workspace, File output) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        String serial = argv.get(1);
        String command = argv.get(2);
        if ("uninstall".equals(command))
            return step(client, shell(serial, "cmd package " + quoted(argv.subList(2, argv.size()))),
                    deadline, workspace, output);

        if (argv.size() < 4) return PicoAdbClient.Result.validation("install requires a workspace APK path");
        String source = argv.get(argv.size() - 1);
        File apk = new File(source);
        if (!apk.isAbsolute()) apk = new File(workspace, source);
        if (!apk.isFile() || !apk.canRead())
            return PicoAdbClient.Result.validation("APK file is not readable: " + source);
        List<String> options = new ArrayList<>();
        for (String option : argv.subList(3, argv.size() - 1)) {
            if ("--no-streaming".equals(option) || "--no-fastdeploy".equals(option)) continue;
            if ("--streaming".equals(option) || option.contains("fastdeploy") || option.endsWith("-agent")
                    || "--incremental".equals(option))
                return PicoAdbClient.Result.validation("This Android ADB engine uses file transfer installation; unsupported flag: " + option);
            options.add(option);
        }
        String staging = "/data/local/tmp/picoadb-install-" + UUID.randomUUID();
        PicoAdbClient.Result created = step(client, shell(serial, "mkdir -- " + quote(staging)), deadline, workspace, output);
        if (!created.success()) return created;
        try {
            PicoAdbClient.Result pushed = step(client, Arrays.asList("-s", serial, "push", apk.getAbsolutePath(), staging + "/base.apk"),
                    deadline, workspace, output);
            if (!pushed.success()) return pushed;
            List<String> install = new ArrayList<>(Arrays.asList("cmd", "package", "install"));
            install.addAll(options); install.add(staging + "/base.apk");
            return step(client, shell(serial, quoted(install)), deadline, workspace, output);
        } finally {
            // Only remove this invocation's random staging directory. Cleanup has its own short grace.
            step(client, shell(serial, "rm -rf -- " + quote(staging)), SystemClock.elapsedRealtime() + 2000L, workspace, output);
        }
    }

    private static PicoAdbClient.Result step(PicoAdbClient client, List<String> command, long deadline,
                                             File workspace, File output) throws Exception {
        long remaining = deadline - SystemClock.elapsedRealtime();
        if (remaining < 100L) return PicoAdbClient.Result.failure(-1, true, "ADB package command timed out");
        File part = File.createTempFile("adb-package-", ".log", output.getParentFile());
        try {
            PicoAdbClient.Result result = client.runInWorkspace(command, remaining, workspace, part);
            try (FileInputStream input = new FileInputStream(part); FileOutputStream log = new FileOutputStream(output, true)) {
                byte[] bytes = new byte[8192]; int count;
                while ((count = input.read(bytes)) >= 0) log.write(bytes, 0, count);
            }
            return result;
        } finally { part.delete(); }
    }

    private static List<String> shell(String serial, String command) {
        return Arrays.asList("-s", serial, "shell", "-T", "-n", command);
    }

    static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
    private static String quoted(List<String> values) {
        StringBuilder command = new StringBuilder();
        for (String value : values) { if (command.length() > 0) command.append(' '); command.append(quote(value)); }
        return command.toString();
    }
}
