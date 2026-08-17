package com.mcpocket.poc;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

final class NodeRuntimeState {
    private static final String DIRECTORY = "node-runtime";
    private static final String STATUS_FILE = "status.json";

    private NodeRuntimeState() {
    }

    static JSONObject read(Context context) {
        File file = statusFile(context);
        if (!file.isFile()) {
            return state("status", "stopped", "running", false);
        }

        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
        } catch (Exception error) {
            return state(
                    "status", "unknown",
                    "running", false,
                    "error", error.getClass().getSimpleName() + ": " + error.getMessage());
        }
    }

    static JSONObject state(Object... pairs) {
        JSONObject result = new JSONObject();
        try {
            for (int index = 0; index + 1 < pairs.length; index += 2) {
                result.put(String.valueOf(pairs[index]), pairs[index + 1]);
            }
        } catch (Exception error) {
            throw new IllegalStateException("Unable to create Node runtime state", error);
        }
        return result;
    }

    static void write(Context context, JSONObject state) {
        try {
            File file = statusFile(context);
            File directory = file.getParentFile();
            if (directory != null && !directory.isDirectory()) {
                directory.mkdirs();
            }
            File temporary = new File(file.getParentFile(), STATUS_FILE + ".tmp");
            try (FileOutputStream output = new FileOutputStream(temporary, false)) {
                output.write(state.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            if (!temporary.renameTo(file)) {
                try (FileOutputStream output = new FileOutputStream(file, false)) {
                    output.write(state.toString(2).getBytes(StandardCharsets.UTF_8));
                }
                temporary.delete();
            }
        } catch (Exception ignored) {
        }
    }

    private static File statusFile(Context context) {
        return new File(new File(context.getFilesDir(), DIRECTORY), STATUS_FILE);
    }
}
