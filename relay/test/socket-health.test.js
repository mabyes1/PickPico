import assert from "node:assert/strict";
import test from "node:test";

import { pendingEntriesForSocket, selectHealthiestSocket } from "../src/socket-health.js";

test("selects the freshest healthy socket", () => {
  const sockets = [{ id: "stale", readyState: 1 }, { id: "older", readyState: 1 }, { id: "fresh", readyState: 1 }];
  const ages = new Map([
    [sockets[0], 60_000],
    [sockets[1], 20_000],
    [sockets[2], 2_000],
  ]);

  assert.equal(selectHealthiestSocket(sockets, (socket) => ages.get(socket), 35_000), sockets[2]);
});

test("returns null when every socket is stale", () => {
  const sockets = [{ id: "stale-a", readyState: 1 }, { id: "stale-b", readyState: 1 }];
  assert.equal(selectHealthiestSocket(sockets, () => 35_001, 35_000), null);
});

test("ignores sockets without a finite heartbeat age", () => {
  const sockets = [{ id: "unknown", readyState: 1 }, { id: "healthy", readyState: 1 }];
  const ages = new Map([
    [sockets[0], Number.NaN],
    [sockets[1], 4_000],
  ]);
  assert.equal(selectHealthiestSocket(sockets, (socket) => ages.get(socket), 35_000), sockets[1]);
});

test("returns only pending requests owned by the disconnected socket", () => {
  const socketA = { id: "a" };
  const socketB = { id: "b" };
  const pending = new Map([
    ["a1", { socket: socketA }],
    ["b1", { socket: socketB }],
    ["a2", { socket: socketA }],
  ]);

  assert.deepEqual(
    pendingEntriesForSocket(pending, socketA).map(([requestId]) => requestId),
    ["a1", "a2"],
  );
});

test("does not route requests to a closing socket with a fresh heartbeat", () => {
  const closing = { readyState: 2 };
  const open = { readyState: 1 };
  assert.equal(selectHealthiestSocket([closing, open], (socket) => socket === closing ? 0 : 1000, 35000), open);
  assert.equal(selectHealthiestSocket([{ readyState: 0 }, closing, { readyState: 3 }], () => 0, 35000), null);
});
