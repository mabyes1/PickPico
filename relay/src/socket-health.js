export function selectHealthiestSocket(sockets, heartbeatAgeMs, staleAfterMs) {
  return [...sockets]
    .filter((socket) => socket.readyState === 1)
    .map((socket) => ({ socket, ageMs: heartbeatAgeMs(socket) }))
    .filter(({ ageMs }) => Number.isFinite(ageMs) && ageMs <= staleAfterMs)
    .sort((left, right) => left.ageMs - right.ageMs)[0]?.socket || null;
}

// A phone that is Dozing can stall its heartbeat timer for a minute or more while the
// socket still delivers inbound frames. Give a late-but-connected socket more time to
// ACK a request instead of failing it as offline.
export function deliveryAckTimeoutMs(heartbeatAgeMs, freshMs, normalMs, lateMs) {
  return Number.isFinite(heartbeatAgeMs) && heartbeatAgeMs > freshMs ? lateMs : normalMs;
}

export function pendingEntriesForSocket(pending, socket) {
  return [...pending.entries()].filter(([, entry]) => entry?.socket === socket);
}
