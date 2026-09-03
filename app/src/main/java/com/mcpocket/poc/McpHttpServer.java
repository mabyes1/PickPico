package com.mcpocket.poc;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

final class McpHttpServer {
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final int MAX_LINE_BYTES = 8 * 1024;
    private static final int SOCKET_TIMEOUT_MS = 10_000;

    private final int port;
    private final String token;
    private final McpProtocol protocol;
    private final ExecutorService workers = Executors.newFixedThreadPool(4);
    private final AtomicLong callCount = new AtomicLong();
    private volatile boolean running;
    private ServerSocket serverSocket;
    private Thread acceptThread;

    McpHttpServer(int port, String token, McpToolActions actions) throws JSONException {
        this.port = port;
        this.token = token;
        this.protocol = new McpProtocol(actions, callCount);
    }

    synchronized void start() throws IOException {
        if (running) {
            return;
        }
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress("0.0.0.0", port));
        serverSocket = socket;
        running = true;
        acceptThread = new Thread(this::acceptLoop, "mcpocket-accept");
        acceptThread.start();
    }

    synchronized void stop() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
            serverSocket = null;
        }
        workers.shutdownNow();
        if (acceptThread != null) {
            acceptThread.interrupt();
            acceptThread = null;
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                socket.setSoTimeout(SOCKET_TIMEOUT_MS);
                workers.execute(() -> handleConnection(socket));
            } catch (IOException error) {
                if (running) {
                    error.printStackTrace();
                }
            }
        }
    }

    private void handleConnection(Socket socket) {
        try (Socket closeable = socket;
             BufferedInputStream input = new BufferedInputStream(closeable.getInputStream());
             BufferedOutputStream output = new BufferedOutputStream(closeable.getOutputStream())) {
            HttpRequest request = readRequest(input);
            if (!"/mcp".equals(request.path)) {
                writeText(output, 404, "Not Found", "text/plain; charset=utf-8", "Not found", null);
                return;
            }

            String origin = request.header("origin");
            if (!isAllowedOrigin(origin)) {
                writeText(output, 403, "Forbidden", "text/plain; charset=utf-8", "Origin rejected", null);
                return;
            }

            if ("OPTIONS".equals(request.method)) {
                Map<String, String> headers = corsHeaders(origin);
                headers.put("Access-Control-Allow-Methods", "POST, OPTIONS");
                headers.put("Access-Control-Allow-Headers",
                        "Authorization, Content-Type, Accept, MCP-Protocol-Version, Mcp-Method, Mcp-Name, X-PickPico-Tool-Profile");
                writeText(output, 204, "No Content", null, "", headers);
                return;
            }

            if (!"POST".equals(request.method)) {
                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("Allow", "POST, OPTIONS");
                writeText(output, 405, "Method Not Allowed", "text/plain; charset=utf-8",
                        "Only POST is supported", headers);
                return;
            }

            if (!isAuthorized(request.header("authorization"))) {
                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("WWW-Authenticate", "Bearer realm=\"PickPico\"");
                headers.putAll(corsHeaders(origin));
                writeText(output, 401, "Unauthorized", "text/plain; charset=utf-8", "Unauthorized", headers);
                return;
            }

            JSONObject json;
            try {
                json = new JSONObject(new String(request.body, StandardCharsets.UTF_8));
            } catch (JSONException error) {
                writeText(output, 400, "Bad Request", "application/json; charset=utf-8",
                        McpProtocol.parseError().toString(), corsHeaders(origin));
                return;
            }
            String method = json.optString("method", "");
            String headerMethod = request.header("mcp-method");
            if (headerMethod != null && !MessageDigest.isEqual(
                    headerMethod.getBytes(StandardCharsets.UTF_8), method.getBytes(StandardCharsets.UTF_8))) {
                writeProtocolError(output, json, -32001, "Mcp-Method header does not match request body", origin);
                return;
            }
            String protocolVersion = request.header("mcp-protocol-version");
            if (McpProtocol.MODERN_VERSION.equals(protocolVersion) && headerMethod == null) {
                writeProtocolError(output, json, -32001, "Mcp-Method header is required", origin);
                return;
            }
            if ("tools/call".equals(method)) {
                String name = json.optJSONObject("params") == null
                        ? ""
                        : json.optJSONObject("params").optString("name", "");
                String headerName = request.header("mcp-name");
                if (headerName != null && !MessageDigest.isEqual(
                        headerName.getBytes(StandardCharsets.UTF_8), name.getBytes(StandardCharsets.UTF_8))) {
                    writeProtocolError(output, json, -32001, "Mcp-Name header does not match request body", origin);
                    return;
                }
                if (McpProtocol.MODERN_VERSION.equals(protocolVersion) && headerName == null) {
                    writeProtocolError(output, json, -32001, "Mcp-Name header is required", origin);
                    return;
                }
            }

            String toolProfile = request.header("x-pickpico-tool-profile");
            McpProtocol.Response response = protocol.handle(json, protocolVersion, toolProfile);
            Map<String, String> headers = corsHeaders(origin);
            headers.put("MCP-Protocol-Version", response.protocolVersion);
            if (response.body == null) {
                writeText(output, response.httpStatus, "Accepted", null, "", headers);
            } else {
                writeText(output, response.httpStatus, "OK", "application/json; charset=utf-8",
                        response.body.toString(), headers);
            }
        } catch (Exception error) {
            error.printStackTrace();
        }
    }

    private void writeProtocolError(
            BufferedOutputStream output,
            JSONObject request,
            int code,
            String message,
            String origin) throws IOException, JSONException {
        JSONObject body = McpProtocol.error(request.opt("id"), code, message);
        writeText(output, 400, "Bad Request", "application/json; charset=utf-8",
                body.toString(), corsHeaders(origin));
    }

    private boolean isAuthorized(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return false;
        }
        byte[] expected = token.getBytes(StandardCharsets.UTF_8);
        byte[] actual = authorization.substring(7).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    private static boolean isAllowedOrigin(String origin) {
        if (origin == null || origin.isEmpty() || "null".equals(origin)) {
            return true;
        }
        String lower = origin.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://localhost:")
                || lower.startsWith("https://localhost:")
                || lower.startsWith("http://127.0.0.1:")
                || lower.startsWith("https://127.0.0.1:");
    }

    private static Map<String, String> corsHeaders(String origin) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (origin != null && !origin.isEmpty()) {
            headers.put("Access-Control-Allow-Origin", origin);
            headers.put("Access-Control-Expose-Headers", "MCP-Protocol-Version");
            headers.put("Vary", "Origin");
        }
        return headers;
    }

    private static HttpRequest readRequest(BufferedInputStream input) throws IOException {
        String requestLine = readLine(input);
        if (requestLine == null || requestLine.isEmpty()) {
            throw new EOFException("Missing request line");
        }
        String[] parts = requestLine.split(" ", 3);
        if (parts.length != 3 || !parts[2].startsWith("HTTP/1.")) {
            throw new IOException("Invalid HTTP request line");
        }
        String path = parts[1].split("\\?", 2)[0];
        Map<String, String> headers = new LinkedHashMap<>();
        for (int count = 0; count < 64; count++) {
            String line = readLine(input);
            if (line == null) {
                throw new EOFException("Unexpected end of headers");
            }
            if (line.isEmpty()) {
                break;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new IOException("Invalid HTTP header");
            }
            headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                    line.substring(colon + 1).trim());
        }

        int length = 0;
        String contentLength = headers.get("content-length");
        if (contentLength != null) {
            try {
                length = Integer.parseInt(contentLength);
            } catch (NumberFormatException error) {
                throw new IOException("Invalid Content-Length", error);
            }
        }
        if (length < 0 || length > MAX_BODY_BYTES) {
            throw new IOException("Request body too large");
        }
        byte[] body = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = input.read(body, offset, length - offset);
            if (read < 0) {
                throw new EOFException("Unexpected end of request body");
            }
            offset += read;
        }
        return new HttpRequest(parts[0].toUpperCase(Locale.ROOT), path, headers, body);
    }

    private static String readLine(BufferedInputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int previous = -1;
        while (bytes.size() <= MAX_LINE_BYTES) {
            int current = input.read();
            if (current < 0) {
                return bytes.size() == 0 ? null : bytes.toString(StandardCharsets.ISO_8859_1.name());
            }
            if (previous == '\r' && current == '\n') {
                byte[] raw = bytes.toByteArray();
                return new String(raw, 0, Math.max(0, raw.length - 1), StandardCharsets.ISO_8859_1);
            }
            bytes.write(current);
            previous = current;
        }
        throw new IOException("HTTP line too long");
    }

    private static void writeText(
            BufferedOutputStream output,
            int status,
            String reason,
            String contentType,
            String body,
            Map<String, String> extraHeaders) throws IOException {
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        StringBuilder headers = new StringBuilder()
                .append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
                .append("Connection: close\r\n")
                .append("Content-Length: ").append(bytes.length).append("\r\n");
        if (contentType != null) {
            headers.append("Content-Type: ").append(contentType).append("\r\n");
        }
        if (extraHeaders != null) {
            for (Map.Entry<String, String> header : extraHeaders.entrySet()) {
                headers.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
            }
        }
        headers.append("\r\n");
        output.write(headers.toString().getBytes(StandardCharsets.ISO_8859_1));
        output.write(bytes);
        output.flush();
    }

    private static final class HttpRequest {
        final String method;
        final String path;
        final Map<String, String> headers;
        final byte[] body;

        HttpRequest(String method, String path, Map<String, String> headers, byte[] body) {
            this.method = method;
            this.path = path;
            this.headers = headers;
            this.body = body;
        }

        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }
}
