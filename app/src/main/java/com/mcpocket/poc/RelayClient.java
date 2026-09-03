package com.mcpocket.poc;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Outbound reverse relay for MCPocket.
 *
 * The phone only makes outbound WSS/HTTPS connections. Incoming public MCP POSTs are carried over
 * that socket and replayed against the existing loopback MCP server. The public relay URL is a
 * high-entropy capability URL; the relay client injects the node's local bearer token only for the
 * loopback hop so clients such as ChatGPT do not need custom HTTP-header authentication support.
 */
final class RelayClient {
    interface Listener {
        void onRelayState(String status, String remoteEndpoint, String detail);
    }

    private static final String PREF_NODE_ID = "relay_node_id";
    private static final String PREF_NODE_SECRET = "relay_node_secret";
    private static final long[] RECONNECT_DELAYS_MS = {1000L, 2000L, 4000L, 8000L, 16000L, 30000L};

    private final Context context;
    private final String relayBaseUrl;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService requestExecutor = Executors.newFixedThreadPool(2);
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(31, TimeUnit.MINUTES)
            .writeTimeout(31, TimeUnit.MINUTES)
            .pingInterval(20, TimeUnit.SECONDS)
            .build();
    private final String nodeId;
    private final String nodeSecret;
    private final String remoteEndpoint;

    private volatile WebSocket webSocket;
    private volatile boolean closed;
    private int reconnectAttempt;

    RelayClient(Context context, String relayBaseUrl, Listener listener) {
        this.context = context.getApplicationContext();
        this.relayBaseUrl = normalizeBaseUrl(relayBaseUrl);
        this.listener = listener;
        SharedPreferences prefs = this.context.getSharedPreferences(McpNodeService.PREFS, Context.MODE_PRIVATE);
        this.nodeId = getOrCreateSecret(prefs, PREF_NODE_ID, 16);
        this.nodeSecret = getOrCreateSecret(prefs, PREF_NODE_SECRET, 32);
        // Keep the node transport on v1, but version the public MCP endpoint separately.
        // ChatGPT/OpenAI may retain a tool schema for a previously seen MCP URL, so a
        // deliberate public-schema version bump gives schema-breaking changes a clean
        // cache boundary without rotating the node identity or relay secret.
        this.remoteEndpoint = this.relayBaseUrl + "/v2/nodes/" + nodeId + "/mcp";
    }

    void start() {
        closed = false;
        reconnectAttempt = 0;
        connect();
    }

    void close() {
        closed = true;
        mainHandler.removeCallbacksAndMessages(null);
        WebSocket socket = webSocket;
        webSocket = null;
        if (socket != null) {
            socket.close(1000, "MCPocket node stopped");
        }
        requestExecutor.shutdownNow();
        listener.onRelayState("stopped", "", "relay stopped");
    }

    private void connect() {
        if (closed) {
            return;
        }
        listener.onRelayState("connecting", remoteEndpoint, "connecting to relay");
        Request request = new Request.Builder()
                .url(toWebSocketUrl(relayBaseUrl) + "/v1/nodes/" + nodeId + "/connect")
                .header("X-MCPocket-Relay-Secret", nodeSecret)
                .build();
        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                reconnectAttempt = 0;
                listener.onRelayState("connected", remoteEndpoint, "remote endpoint online");
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                handleRelayMessage(webSocket, text);
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                RelayClient.this.webSocket = null;
                if (!closed) {
                    listener.onRelayState("disconnected", remoteEndpoint, "relay closed: " + reason);
                    scheduleReconnect();
                }
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable error, Response response) {
                RelayClient.this.webSocket = null;
                if (!closed) {
                    listener.onRelayState("disconnected", remoteEndpoint,
                            "relay error: " + error.getClass().getSimpleName());
                    scheduleReconnect();
                }
            }
        });
    }

    private void scheduleReconnect() {
        int index = Math.min(reconnectAttempt, RECONNECT_DELAYS_MS.length - 1);
        long delay = RECONNECT_DELAYS_MS[index];
        reconnectAttempt++;
        mainHandler.postDelayed(this::connect, delay);
    }

    private void handleRelayMessage(WebSocket socket, String text) {
        final JSONObject envelope;
        try {
            envelope = new JSONObject(text);
        } catch (JSONException error) {
            return;
        }
        if (!"request".equals(envelope.optString("type", ""))) {
            return;
        }
        requestExecutor.execute(() -> proxyToLoopback(socket, envelope));
    }

    private void proxyToLoopback(WebSocket socket, JSONObject envelope) {
        String requestId = envelope.optString("requestId", "");
        if (requestId.isEmpty()) {
            return;
        }
        try {
            String bodyText = envelope.optString("body", "");
            JSONObject incomingHeaders = envelope.optJSONObject("headers");
            String contentType = header(incomingHeaders, "content-type", "application/json; charset=utf-8");
            Request.Builder request = new Request.Builder()
                    .url("http://127.0.0.1:8765/mcp")
                    .post(RequestBody.create(bodyText, MediaType.parse(contentType)));

            String localToken = context
                    .getSharedPreferences(McpNodeService.PREFS, Context.MODE_PRIVATE)
                    .getString(McpNodeService.KEY_TOKEN, "");
            if (!localToken.isEmpty()) {
                request.header("Authorization", "Bearer " + localToken);
            }
            copyHeader(incomingHeaders, request, "accept");
            copyHeader(incomingHeaders, request, "mcp-protocol-version");
            copyHeader(incomingHeaders, request, "mcp-method");
            copyHeader(incomingHeaders, request, "mcp-name");

            try (Response response = client.newCall(request.build()).execute()) {
                String responseBody = response.body() == null ? "" : response.body().string();
                JSONObject responseHeaders = new JSONObject();
                putHeader(response.headers(), responseHeaders, "Content-Type");
                putHeader(response.headers(), responseHeaders, "MCP-Protocol-Version");
                JSONObject result = new JSONObject()
                        .put("type", "response")
                        .put("requestId", requestId)
                        .put("status", response.code())
                        .put("headers", responseHeaders)
                        .put("body", responseBody);
                socket.send(result.toString());
            }
        } catch (Exception error) {
            try {
                socket.send(new JSONObject()
                        .put("type", "response")
                        .put("requestId", requestId)
                        .put("status", 502)
                        .put("headers", new JSONObject().put("content-type", "application/json"))
                        .put("body", new JSONObject()
                                .put("error", "loopback_proxy_failed")
                                .put("message", error.getClass().getSimpleName())
                                .toString())
                        .toString());
            } catch (JSONException ignored) {
            }
        }
    }

    private static void copyHeader(JSONObject headers, Request.Builder request, String lowerName) {
        if (headers == null) {
            return;
        }
        String value = headers.optString(lowerName, "");
        if (!value.isEmpty()) {
            request.header(canonicalHeader(lowerName), value);
        }
    }

    private static void putHeader(Headers source, JSONObject target, String name) throws JSONException {
        String value = source.get(name);
        if (value != null && !value.isEmpty()) {
            target.put(name.toLowerCase(Locale.ROOT), value);
        }
    }

    private static String header(JSONObject headers, String name, String fallback) {
        if (headers == null) {
            return fallback;
        }
        String value = headers.optString(name, "");
        return value.isEmpty() ? fallback : value;
    }

    private static String canonicalHeader(String lowerName) {
        if ("authorization".equals(lowerName)) return "Authorization";
        if ("accept".equals(lowerName)) return "Accept";
        if ("mcp-protocol-version".equals(lowerName)) return "MCP-Protocol-Version";
        if ("mcp-method".equals(lowerName)) return "Mcp-Method";
        if ("mcp-name".equals(lowerName)) return "Mcp-Name";
        return lowerName;
    }

    private static String getOrCreateSecret(SharedPreferences prefs, String key, int byteCount) {
        String existing = prefs.getString(key, "");
        if (existing != null && !existing.isEmpty()) {
            return existing;
        }
        byte[] bytes = new byte[byteCount];
        new SecureRandom().nextBytes(bytes);
        String value = Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        prefs.edit().putString(key, value).apply();
        return value;
    }

    private static String normalizeBaseUrl(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        if (!result.startsWith("https://") && !result.startsWith("http://")) {
            throw new IllegalArgumentException("Relay URL must start with https:// or http://");
        }
        return result;
    }

    private static String toWebSocketUrl(String httpUrl) {
        if (httpUrl.startsWith("https://")) {
            return "wss://" + httpUrl.substring("https://".length());
        }
        return "ws://" + httpUrl.substring("http://".length());
    }
}
