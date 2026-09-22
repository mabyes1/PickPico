// The relay cannot know whether Android was killed, asleep, or disconnected.
// Preserve the observable evidence instead of collapsing it into Internal error.
export function offlineResult(rpc, socketCount, heartbeatAges) {
  const ages = heartbeatAges.filter(Number.isFinite);
  const state = {
    error: "node_offline",
    reason: socketCount ? "heartbeat_stale" : "no_device_connection",
    lastHeartbeatAgeMs: ages.length ? Math.min(...ages) : null,
    causeConfidence: "unknown",
    commandDispatched: false,
    recovery: "Wake or open PickPico, then read server_info.connectionDiagnostics. Do not assume the app was killed or that an action ran.",
  };
  if (rpc?.method === "tools/call" && rpc.id !== undefined) {
    return { jsonrpc: "2.0", id: rpc.id, result: {
      isError: true, content: [{ type: "text", text: JSON.stringify(state) }], structuredContent: state,
    } };
  }
  return { jsonrpc: "2.0", id: rpc?.id ?? null, error: { code: -32001, message: "PickPico device offline", data: state } };
}
