package com.mcpocket.poc;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

final class SelfUpdateState {
    private static final String DIRECTORY = "self-update";
    private static final String STATUS_FILE = "status.json";

    private SelfUpdateState() {
    }

    static JSONObject read(Context context) {
        File file = statusFile(context);
        if (!file.isFile()) {
            return json("idle", false);
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
            JSONObject state = json("unknown", false);
            put(state, "error", error.getClass().getSimpleName() + ": " + error.getMessage());
            return state;
        }
    }

    static void write(Context context, JSONObject state) {
        try {
            File file = statusFile(context);
            File directory = file.getParentFile();
            if (directory != null && !directory.isDirectory()) {
                directory.mkdirs();
            }
            File temporary = new File(directory, STATUS_FILE + ".tmp");
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

    static File candidateFile(Context context) {
        File directory = new File(context.getFilesDir(), DIRECTORY);
        if (!directory.isDirectory()) {
            directory.mkdirs();
        }
        return new File(directory, "candidate.apk");
    }

    static JSONObject json(String status, boolean running) {
        JSONObject state = new JSONObject();
        put(state, "status", status);
        put(state, "running", running);
        return state;
    }

    static void put(JSONObject object, String key, Object value) {
        try {
            object.put(key, value);
        } catch (Exception ignored) {
        }
    }

    private static File statusFile(Context context) {
        return new File(new File(context.getFilesDir(), DIRECTORY), STATUS_FILE);
    }
}
