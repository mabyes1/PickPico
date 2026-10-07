package com.mcpocket.poc;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Discovers Android Wireless Debugging endpoints advertised by adbd.
 *
 * Pairing and authenticated ADB transport deliberately live outside this class.
 * This class owns only Android NSD lifecycle and the current endpoint snapshot.
 */
final class PicoAdbDiscovery {
    static final String PAIRING_TYPE = "_adb-tls-pairing._tcp.";
    static final String CONNECT_TYPE = "_adb-tls-connect._tcp.";

    private final NsdManager nsd;
    private final Map<String, Endpoint> pairing = new LinkedHashMap<>();
    private final Map<String, Endpoint> connect = new LinkedHashMap<>();
    private NsdManager.DiscoveryListener pairingListener;
    private NsdManager.DiscoveryListener connectListener;
    private boolean started;
    private String lastError = "";

    PicoAdbDiscovery(Context context) {
        nsd = (NsdManager) context.getApplicationContext().getSystemService(Context.NSD_SERVICE);
    }

    synchronized void start() {
        if (started || nsd == null) return;
        started = true;
        pairingListener = listener(PAIRING_TYPE, pairing);
        connectListener = listener(CONNECT_TYPE, connect);
        try {
            nsd.discoverServices(PAIRING_TYPE, NsdManager.PROTOCOL_DNS_SD, pairingListener);
            nsd.discoverServices(CONNECT_TYPE, NsdManager.PROTOCOL_DNS_SD, connectListener);
        } catch (RuntimeException error) {
            lastError = error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
            stop();
        }
    }

    synchronized void stop() {
        stopListener(pairingListener);
        stopListener(connectListener);
        pairingListener = null;
        connectListener = null;
        started = false;
    }

    synchronized JSONObject status() throws JSONException {
        return new JSONObject()
                .put("discoveryStarted", started)
                .put("pairingServiceType", PAIRING_TYPE)
                .put("connectServiceType", CONNECT_TYPE)
                .put("pairingEndpoints", endpoints(pairing))
                .put("connectEndpoints", endpoints(connect))
                .put("lastError", lastError);
    }

    private NsdManager.DiscoveryListener listener(String type, Map<String, Endpoint> target) {
        return new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String serviceType) {
                synchronized (PicoAdbDiscovery.this) { lastError = ""; }
            }

            @Override public void onServiceFound(NsdServiceInfo serviceInfo) {
                if (serviceInfo == null || !sameType(type, serviceInfo.getServiceType())) return;
                resolve(serviceInfo, target);
            }

            @Override public void onServiceLost(NsdServiceInfo serviceInfo) {
                if (serviceInfo == null) return;
                synchronized (PicoAdbDiscovery.this) {
                    target.remove(serviceInfo.getServiceName());
                }
            }

            @Override public void onDiscoveryStopped(String serviceType) {
            }

            @Override public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                synchronized (PicoAdbDiscovery.this) {
                    lastError = "NSD start failed " + errorCode + " for " + serviceType;
                }
            }

            @Override public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                synchronized (PicoAdbDiscovery.this) {
                    lastError = "NSD stop failed " + errorCode + " for " + serviceType;
                }
            }
        };
    }

    @SuppressWarnings("deprecation")
    private void resolve(NsdServiceInfo serviceInfo, Map<String, Endpoint> target) {
        try {
            nsd.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                @Override public void onResolveFailed(NsdServiceInfo failed, int errorCode) {
                    synchronized (PicoAdbDiscovery.this) {
                        lastError = "NSD resolve failed " + errorCode + " for "
                                + (failed == null ? "unknown" : failed.getServiceName());
                    }
                }

                @Override public void onServiceResolved(NsdServiceInfo resolved) {
                    InetAddress host = resolved.getHost();
                    if (host == null || resolved.getPort() <= 0) return;
                    synchronized (PicoAdbDiscovery.this) {
                        target.put(resolved.getServiceName(), new Endpoint(
                                resolved.getServiceName(),
                                host.getHostAddress(),
                                resolved.getPort()));
                    }
                }
            });
        } catch (RuntimeException error) {
            synchronized (this) {
                lastError = error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
            }
        }
    }

    private synchronized void stopListener(NsdManager.DiscoveryListener listener) {
        if (listener == null || nsd == null) return;
        try {
            nsd.stopServiceDiscovery(listener);
        } catch (RuntimeException ignored) {
        }
    }

    private static boolean sameType(String expected, String actual) {
        if (actual == null) return false;
        String normalizedExpected = expected.endsWith(".") ? expected : expected + ".";
        String normalizedActual = actual.endsWith(".") ? actual : actual + ".";
        return normalizedExpected.equalsIgnoreCase(normalizedActual);
    }

    private static JSONArray endpoints(Map<String, Endpoint> source) throws JSONException {
        JSONArray result = new JSONArray();
        for (Endpoint endpoint : source.values()) {
            result.put(new JSONObject()
                    .put("name", endpoint.name)
                    .put("host", endpoint.host)
                    .put("port", endpoint.port));
        }
        return result;
    }

    private static final class Endpoint {
        final String name;
        final String host;
        final int port;

        Endpoint(String name, String host, int port) {
            this.name = name;
            this.host = host;
            this.port = port;
        }
    }
}
