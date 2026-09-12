package com.mcpocket.poc;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.JSONException;
import org.json.JSONObject;

/** Explicit cursors make retries repeatable without silently advancing shared state. */
final class PagedOutput {
    static JSONObject file(File file, JSONObject args) throws IOException, JSONException {
        String version = version(file);
        long offset = args.optLong("offset", 0);
        if (offset > 0 && !args.has("version"))
            throw new CommandRuntime.CommandInputException("Continuation requires the previous version");
        if (args.has("version") && !version.equals(args.getString("version")))
            throw new CommandRuntime.CommandInputException("FILE_CHANGED: restart from offset 0");
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            long size = input.length();
            if (offset < 0 || offset > size) throw new CommandRuntime.CommandInputException("offset exceeds file size");
            input.seek(offset);
            int budget = args.optInt("maxBytes", 8192);
            byte[] bytes = new byte[(int) Math.min(size - offset, budget + 3L)];
            input.readFully(bytes);
            if (bytes.length > 0 && (bytes[0] & 0xc0) == 0x80)
                throw new CommandRuntime.CommandInputException("offset must be a UTF-8 character boundary");
            int end = Math.min(budget, bytes.length);
            while (end < bytes.length && (bytes[end] & 0xc0) == 0x80) end++;
            String content = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, 0, end)).toString();
            if (!version.equals(version(file))) throw new CommandRuntime.CommandInputException("FILE_CHANGED: restart from offset 0");
            return new JSONObject().put("content", content).put("offset", offset).put("nextOffset", offset + end)
                    .put("bytesRead", end).put("sizeBytes", size).put("version", version).put("truncated", offset + end < size);
        }
    }

    private static String version(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
            }
            StringBuilder result = new StringBuilder();
            for (byte b : digest.digest()) result.append(String.format("%02x", b & 255));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    static JSONObject process(JSONObject result, JSONObject args) throws JSONException {
        int limit = args.optInt("maxChars", 8192);
        for (String stream : new String[]{"stdout", "stderr"}) {
            String text = result.optString(stream);
            long requested = args.optLong(stream + "Offset", 0);
            if (requested > text.length()) throw new CommandRuntime.CommandInputException(stream + "Offset exceeds captured output");
            int offset = (int) requested;
            if (offset > 0 && offset < text.length() && Character.isLowSurrogate(text.charAt(offset)))
                throw new CommandRuntime.CommandInputException(stream + "Offset splits a character");
            int end = (int) Math.min(text.length(), requested + limit);
            if (end < text.length() && end > offset && Character.isHighSurrogate(text.charAt(end - 1))) end--;
            result.put(stream, text.substring(offset, end)).put(stream + "Offset", offset)
                    .put(stream + "NextOffset", end).put(stream + "HasMore", end < text.length());
        }
        return result;
    }
}
