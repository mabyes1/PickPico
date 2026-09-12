package com.mcpocket.poc;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Outbound reverse relay for PickPico.
 *
 * The phone only makes outbound WSS/HTTPS connections. Incoming public MCP POSTs are carried over
 * that socket and replayed against the existing loopback MCP server. The public relay URL is a
 * high-entropy capability URL; the relay client injects the node's local bearer token only for the
 * loopback hop so clients such as ChatGPT do not need custom HTTP-header authentication support.
 */
final class RelayClient {

    interface Listener {
        void onRelayState(String status, String remoteEndpoint, String detail);
        void onLoopbackProxyFailure(String requestId, Exception error);
    }

    private static final String PREF_NODE_ID = "relay_node_id";
    private static final String PREF_NODE_SECRET = "relay_node_secret";
    private static final long[] RECONNECT_DELAYS_MS = {1000L, 2000L, 4000L, 8000L, 16000L, 30000L};
    private static final long HEARTBEAT_INTERVAL_MS = 10_000L;
    private static final long HEARTBEAT_TIMEOUT_MS = 15_000L;

    private final Context context;
    private final String relayBaseUrl;
    private final Listener listener;
    private final ConnectivityManager connectivityManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    // HUMAN HELP and picker calls wait for people. Keep spare workers available for
    // status and independent commands while an interactive request is pending.
    private final ExecutorService requestExecutor;
    private final Call.Factory loopbackCalls;
    // Accessed only under this client's monitor. Socket identity is the request's
    // connection generation; a reconnected socket never inherits old work.
    private final Map<String, PendingProxy> pendingProxies = new LinkedHashMap<>();
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
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
    private long reconnectAttemptCount;
    private volatile String pendingHeartbeatNonce;
    private volatile ScheduledFuture<?> heartbeatFuture;
    private volatile ScheduledFuture<?> heartbeatTimeoutFuture;
    private ConnectivityManager.NetworkCallback networkCallback;
    private Network activeNetwork;
    private String currentNetworkType = "unknown";
    private String connectionId = "";
    private String relayConnectedAt = "";
    private String lastRelayHeartbeatAt = "";
    private String lastRelayPongAt = "";
    private String lastRelayDisconnectAt = "";
    private String lastRelayDisconnectReason = "";
    private String lastLoopbackProxyErrorAt = "";
    private String lastLoopbackProxyErrorType = "";
    private String lastLoopbackProxyErrorMessage = "";
    private String lastLoopbackProxyRequestId = "";
    private volatile boolean loopbackHealthy = true;

    RelayClient(Context context, String relayBaseUrl, Listener listener) {
        this(context, relayBaseUrl, listener, Executors.newFixedThreadPool(4), null);
    }

    RelayClient(Context context, String relayBaseUrl, Listener listener,
                ExecutorService requestExecutor, Call.Factory loopbackCalls) {
        this.context = context.getApplicationContext();
        this.relayBaseUrl = normalizeBaseUrl(relayBaseUrl);
        this.listener = listener;
        this.requestExecutor = requestExecutor;
        // Retrying a lost POST can repeat a side effect whose response was lost.
        this.loopbackCalls = loopbackCalls != null ? loopbackCalls
                : client.newBuilder().retryOnConnectionFailure(false).build();
        this.connectivityManager = (ConnectivityManager) this.context.getSystemService(Context.CONNECTIVITY_SERVICE);
        SharedPreferences prefs = this.context.getSharedPreferences(McpNodeService.PREFS, Context.MODE_PRIVATE);
        this.nodeId = getOrCreateSecret(prefs, PREF_NODE_ID, 16);
        this.nodeSecret = getOrCreateSecret(prefs, PREF_NODE_SECRET, 32);
        // Keep the node transport on v1, but version the public MCP endpoint separately.
        // ChatGPT/OpenAI may retain a tool schema for a previously seen MCP URL, so a
        // deliberate public-schema version bump gives schema-breaking changes a clean
        // cache boundary without rotating the node identity or relay secret. v3 is the
        // Thin MCP profile; v1/v2 remain relay-compatible for existing clients.
        this.remoteEndpoint = this.relayBaseUrl + "/v3/nodes/" + nodeId + "/mcp";
    }

    static String migrateLegacyRelayIfNeeded(SharedPreferences prefs, String relayBaseUrl) {
        // Preserve explicitly configured endpoints; never redirect to a project host.
        return normalizeBaseUrl(relayBaseUrl);
    }

    static void resetIdentity(SharedPreferences prefs) {
        prefs.edit()
                .remove(PREF_NODE_ID)
                .remove(PREF_NODE_SECRET)
                .apply();
    }

    synchronized void start() {
        if (webSocket != null) return;
        closed = false;
        reconnectAttempt = 0;
        registerNetworkCallback();
        connect();
    }

    synchronized void close() {
        closed = true;
        mainHandler.removeCallbacksAndMessages(null);
        clearHeartbeatState();
        unregisterNetworkCallback();
        WebSocket socket = webSocket;
        webSocket = null;
        cancelPendingProxies(null);
        if (socket != null) {
            socket.close(1000, "PickPico node stopped");
        }
        requestExecutor.shutdownNow();
        heartbeatExecutor.shutdownNow();
        listener.onRelayState("stopped", "", "relay stopped");
    }

    synchronized JSONObject diagnostics() throws JSONException {
        String observedNetworkType = currentNetworkSnapshotType();
        return new JSONObject()
                .put("connectionId", connectionId)
                .put("relayConnectedAt", relayConnectedAt)
                .put("lastRelayHeartbeatAt", lastRelayHeartbeatAt)
                .put("lastRelayPongAt", lastRelayPongAt)
                .put("lastRelayDisconnectAt", lastRelayDisconnectAt)
                .put("lastRelayDisconnectReason", lastRelayDisconnectReason)
                .put("lastLoopbackProxyErrorAt", lastLoopbackProxyErrorAt)
                .put("lastLoopbackProxyErrorType", lastLoopbackProxyErrorType)
                .put("lastLoopbackProxyErrorMessage", lastLoopbackProxyErrorMessage)
                .put("lastLoopbackProxyRequestId", lastLoopbackProxyRequestId)
                .put("loopbackHealthy", loopbackHealthy)
                .put("reconnectAttemptCount", reconnectAttemptCount)
                .put("reconnectBackoffAttempt", reconnectAttempt)
                .put("currentNetworkType", observedNetworkType)
                .put("socketPresent", webSocket != null);
    }

    private synchronized void connect() {
        if (closed || webSocket != null) {
            return;
        }
        listener.onRelayState("connecting", remoteEndpoint, "connecting to relay");
        connectionId = UUID.randomUUID().toString();
        relayConnectedAt = "";
        Request request = new Request.Builder()
                .url(toWebSocketUrl(relayBaseUrl) + "/v1/nodes/" + nodeId + "/connect")
                .header("X-PickPico-Relay-Secret", nodeSecret)
                .header("X-PickPico-Connection-Id", connectionId)
                .build();
        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                synchronized (RelayClient.this) {
                    if (closed || RelayClient.this.webSocket != webSocket) {
                        webSocket.cancel();
                        return;
                    }
                    listener.onRelayState("verifying", remoteEndpoint, "relay socket open; verifying heartbeat");
                    startHeartbeatLoop(webSocket);
                }
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                // A peer close stops delivery before onClosed. Do not wait for a
                // close handshake (or a cancel callback) to recover the node.
                recover(webSocket, "relay closing: " + code);
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                handleRelayMessage(webSocket, text);
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                recover(webSocket, "relay closed: " + code);
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable error, Response response) {
                recover(webSocket, "relay error: " + error.getClass().getSimpleName());
            }
        });
    }

    private synchronized void recover(WebSocket socket, String detail) {
        if (closed || socket != webSocket) return;
        webSocket = null;
        cancelPendingProxies(socket);
        clearHeartbeatState();
        socket.cancel();
        lastRelayDisconnectAt = Instant.now().toString();
        lastRelayDisconnectReason = detail == null ? "" : detail;
        listener.onRelayState("disconnected", remoteEndpoint, detail);
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        if (closed) return;
        int index = Math.min(reconnectAttempt, RECONNECT_DELAYS_MS.length - 1);
        long delay = RECONNECT_DELAYS_MS[index];
        reconnectAttempt++;
        reconnectAttemptCount++;
        mainHandler.postDelayed(this::connect, delay);
    }

    private synchronized void sendHeartbeat(WebSocket socket) {
        if (closed || socket != webSocket || pendingHeartbeatNonce != null) {
            return;
        }
        String nonce = UUID.randomUUID().toString();
        pendingHeartbeatNonce = nonce;
        lastRelayHeartbeatAt = Instant.now().toString();
        try {
            boolean queued = socket.send(new JSONObject()
                    .put("type", "ping")
                    .put("nonce", nonce)
                    .put("sentAtElapsedMs", SystemClock.elapsedRealtime())
                    .toString());
            if (!queued) {
                pendingHeartbeatNonce = null;
                listener.onRelayState("stale", remoteEndpoint, "relay heartbeat could not be queued");
                recover(socket, "relay heartbeat could not be queued");
                return;
            }
        } catch (JSONException error) {
            pendingHeartbeatNonce = null;
            recover(socket, "relay heartbeat failed");
            return;
        }
        ScheduledFuture<?> previousTimeout = heartbeatTimeoutFuture;
        if (previousTimeout != null) {
            previousTimeout.cancel(false);
        }
        heartbeatTimeoutFuture = heartbeatExecutor.schedule(
                () -> handleHeartbeatTimeout(socket, nonce),
                HEARTBEAT_TIMEOUT_MS,
                TimeUnit.MILLISECONDS);
    }

    private void startHeartbeatLoop(WebSocket socket) {
        clearHeartbeatState();
        sendHeartbeat(socket);
        heartbeatFuture = heartbeatExecutor.scheduleWithFixedDelay(
                () -> sendHeartbeat(socket),
                HEARTBEAT_INTERVAL_MS,
                HEARTBEAT_INTERVAL_MS,
                TimeUnit.MILLISECONDS);
    }

    private synchronized void handleHeartbeatTimeout(WebSocket socket, String nonce) {
        if (closed || socket != webSocket || !nonce.equals(pendingHeartbeatNonce)) {
            return;
        }
        pendingHeartbeatNonce = null;
        listener.onRelayState("stale", remoteEndpoint, "relay heartbeat timed out");
        recover(socket, "relay heartbeat timed out");
    }

    private void clearHeartbeatState() {
        pendingHeartbeatNonce = null;
        ScheduledFuture<?> timeout = heartbeatTimeoutFuture;
        heartbeatTimeoutFuture = null;
        if (timeout != null) {
            timeout.cancel(false);
        }
        ScheduledFuture<?> loop = heartbeatFuture;
        heartbeatFuture = null;
        if (loop != null) {
            loop.cancel(false);
        }
    }

    private synchronized void handleHeartbeatPong(WebSocket socket, JSONObject payload) {
        if (closed || socket != webSocket) {
            return;
        }
        String nonce = payload.optString("nonce", "");
        if (pendingHeartbeatNonce == null || !pendingHeartbeatNonce.equals(nonce)) {
            return;
        }
        pendingHeartbeatNonce = null;
        ScheduledFuture<?> timeout = heartbeatTimeoutFuture;
        heartbeatTimeoutFuture = null;
        if (timeout != null) {
            timeout.cancel(false);
        }
        reconnectAttempt = 0;
        if (relayConnectedAt.isEmpty()) {
            relayConnectedAt = Instant.now().toString();
        }
        lastRelayPongAt = Instant.now().toString();
        listener.onRelayState("connected", remoteEndpoint, "relay heartbeat healthy");
    }

    private synchronized void registerNetworkCallback() {
        if (connectivityManager == null || networkCallback != null) {
            return;
        }
        refreshNetworkSnapshot();
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                handleNetworkAvailable(network);
            }

            @Override
            public void onLost(Network network) {
                handleNetworkLost(network);
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                synchronized (RelayClient.this) {
                    if (activeNetwork != null && activeNetwork.equals(network)) {
                        currentNetworkType = networkType(capabilities);
                    }
                }
            }
        };
        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback);
        } catch (RuntimeException ignored) {
            networkCallback = null;
        }
    }

    private synchronized void unregisterNetworkCallback() {
        if (connectivityManager == null || networkCallback == null) {
            return;
        }
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback);
        } catch (RuntimeException ignored) {
        }
        networkCallback = null;
    }

    private synchronized void handleNetworkAvailable(Network network) {
        if (closed) return;
        Network previous = activeNetwork;
        activeNetwork = network;
        currentNetworkType = networkType(connectivityManager == null
                ? null : connectivityManager.getNetworkCapabilities(network));
        boolean changed = previous == null || !previous.equals(network);
        if (webSocket == null) {
            // A restored network should not sit behind a stale 30-second backoff.
            mainHandler.post(this::connect);
        } else if (changed) {
            recover(webSocket, "active network changed to " + currentNetworkType);
        }
    }

    private synchronized void handleNetworkLost(Network network) {
        if (activeNetwork == null || !activeNetwork.equals(network)) {
            return;
        }
        activeNetwork = null;
        currentNetworkType = "none";
        if (!closed && webSocket != null) {
            recover(webSocket, "active network lost");
        }
    }

    private synchronized void refreshNetworkSnapshot() {
        if (connectivityManager == null) {
            currentNetworkType = "unknown";
            return;
        }
        Network network = connectivityManager.getActiveNetwork();
        activeNetwork = network;
        currentNetworkType = network == null
                ? "none"
                : networkType(connectivityManager.getNetworkCapabilities(network));
    }

    private String currentNetworkSnapshotType() {
        if (connectivityManager == null) return currentNetworkType;
        Network network = connectivityManager.getActiveNetwork();
        return network == null
                ? "none"
                : networkType(connectivityManager.getNetworkCapabilities(network));
    }

    private static String networkType(NetworkCapabilities capabilities) {
        if (capabilities == null) return "unknown";
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return "vpn";
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "wifi";
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "cellular";
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "ethernet";
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) return "bluetooth";
        return "other";
    }

    private synchronized void handleRelayMessage(WebSocket socket, String text) {
        if (closed || socket != webSocket) return;
        final JSONObject envelope;
        try {
            envelope = new JSONObject(text);
        } catch (JSONException error) {
            return;
        }
        if ("pong".equals(envelope.optString("type", ""))) {
            handleHeartbeatPong(socket, envelope);
            return;
        }
        if (!"request".equals(envelope.optString("type", ""))) {
            return;
        }
        String requestId = envelope.optString("requestId", "");
        if (requestId.isEmpty()) {
            return;
        }
        try {
            boolean acknowledged = socket.send(new JSONObject()
                    .put("type", "request_ack")
                    .put("requestId", requestId)
                    .toString());
            if (!acknowledged) {
                recover(socket, "relay acknowledgement could not be queued");
                return;
            }
        } catch (JSONException error) {
            recover(socket, "relay acknowledgement failed");
            return;
        }
        // A duplicate transport envelope must not enqueue the same request twice.
        if (pendingProxies.containsKey(requestId)) return;
        PendingProxy pending = new PendingProxy(socket, envelope, requestId);
        pending.work = new FutureTask<>(() -> {
            try {
                proxyToLoopback(pending);
            } finally {
                pending.lease.close();
                synchronized (RelayClient.this) {
                    if (pendingProxies.get(requestId) == pending) pendingProxies.remove(requestId);
                }
            }
            return null;
        });
        pendingProxies.put(requestId, pending);
        try {
            requestExecutor.execute(pending.work);
        } catch (RejectedExecutionException error) {
            pendingProxies.remove(requestId);
            pending.lease.close();
            pending.work.cancel(false);
            recover(socket, "relay request executor stopped");
        }
    }

    private boolean isCurrentProxy(PendingProxy pending) {
        // Caller must hold this client's monitor.
        return !closed && webSocket == pending.socket
                && pendingProxies.get(pending.requestId) == pending;
    }

    private void cancelPendingProxies(WebSocket socket) {
        // Caller must hold this client's monitor. null cancels all generations.
        Iterator<PendingProxy> iterator = pendingProxies.values().iterator();
        while (iterator.hasNext()) {
            PendingProxy pending = iterator.next();
            if (socket != null && pending.socket != socket) continue;
            iterator.remove();
            pending.lease.close();
            pending.work.cancel(false);
            if (pending.call != null) pending.call.cancel();
        }
        if (requestExecutor instanceof ThreadPoolExecutor) {
            ((ThreadPoolExecutor) requestExecutor).purge();
        }
    }

    private void proxyToLoopback(PendingProxy pending) {
        synchronized (this) {
            if (!isCurrentProxy(pending)) return;
        }
        String requestId = pending.requestId;
        JSONObject envelope = pending.envelope;
        try {
            String bodyText = envelope.optString("body", "");
            JSONObject incomingHeaders = envelope.optJSONObject("headers");
            String contentType = header(incomingHeaders, "content-type", "application/json; charset=utf-8");
            Request.Builder request = new Request.Builder()
                    .url("http://127.0.0.1:8765/mcp")
                    .header(RelayRequestScope.HEADER, pending.lease.id)
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
            copyHeader(incomingHeaders, request, "x-pickpico-tool-profile");

            Call call;
            synchronized (this) {
                if (!isCurrentProxy(pending)) return;
                call = loopbackCalls.newCall(request.build());
                if (!isCurrentProxy(pending)) {
                    call.cancel();
                    return;
                }
                // Register before releasing the lock. A concurrent disconnect can
                // now cancel even a Call whose execute() has not started yet.
                pending.call = call;
            }
            try (Response response = call.execute()) {
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
                synchronized (this) {
                    if (!isCurrentProxy(pending) || call.isCanceled()) return;
                    loopbackHealthy = true;
                    if (!pending.socket.send(result.toString())) {
                        recover(pending.socket, "relay response could not be queued");
                    }
                }
            }
        } catch (Exception error) {
            synchronized (this) {
                // Disconnect cancellation is expected; it must not mark the new
                // connection unhealthy or trigger a local MCP server restart.
                if (!isCurrentProxy(pending)
                        || (pending.call != null && pending.call.isCanceled())) return;
                loopbackHealthy = false;
                recordLoopbackProxyError(requestId, error);
                listener.onLoopbackProxyFailure(requestId, error);
                try {
                    pending.socket.send(new JSONObject()
                            .put("type", "response")
                            .put("requestId", requestId)
                            .put("status", 502)
                            .put("headers", new JSONObject().put("content-type", "application/json"))
                            .put("body", new JSONObject()
                                    .put("error", "loopback_proxy_failed")
                                    .put("executionState", "unknown")
                                    .put("message", error.getClass().getSimpleName())
                                    .toString())
                            .toString());
                } catch (JSONException ignored) {
                }
            }
        }
    }

    private static final class PendingProxy {
        final WebSocket socket;
        final JSONObject envelope;
        final String requestId;
        final RelayRequestScope.Lease lease = RelayRequestScope.createLease();
        FutureTask<Void> work;
        Call call;

        PendingProxy(WebSocket socket, JSONObject envelope, String requestId) {
            this.socket = socket;
            this.envelope = envelope;
            this.requestId = requestId;
        }
    }

    private synchronized void recordLoopbackProxyError(String requestId, Exception error) {
        lastLoopbackProxyErrorAt = Instant.now().toString();
        lastLoopbackProxyRequestId = requestId == null ? "" : requestId;
        lastLoopbackProxyErrorType = error == null ? "unknown" : error.getClass().getName();
        String message = error == null ? "" : error.getMessage();
        lastLoopbackProxyErrorMessage = message == null ? "" : message;
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
        if ("x-pickpico-tool-profile".equals(lowerName)) return "X-PickPico-Tool-Profile";
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

    static String normalizeBaseUrl(String value) {
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
