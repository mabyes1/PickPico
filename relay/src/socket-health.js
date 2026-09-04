export function selectHealthiestSocket(sockets, heartbeatAgeMs, staleAfterMs) {
  return [...sockets]
    .map((socket) => ({ socket, ageMs: heartbeatAgeMs(socket) }))
    .filter(({ ageMs }) => Number.isFinite(ageMs) && ageMs <= staleAfterMs)
    .sort((left, right) => left.ageMs - right.ageMs)[0]?.socket || null;
}

export function pendingEntriesForSocket(pending, socket) {
  return [...pending.entries()].filter(([, entry]) => entry?.socket === socket);
}
