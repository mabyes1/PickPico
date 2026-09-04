import assert from "node:assert/strict";
import test from "node:test";

import { pendingEntriesForSocket, selectHealthiestSocket } from "../src/socket-health.js";

test("selects the freshest healthy socket", () => {
  const sockets = [{ id: "stale" }, { id: "older" }, { id: "fresh" }];
  const ages = new Map([
    [sockets[0], 60_000],
    [sockets[1], 20_000],
    [sockets[2], 2_000],
  ]);

  assert.equal(selectHealthiestSocket(sockets, (socket) => ages.get(socket), 35_000), sockets[2]);
});

test("returns null when every socket is stale", () => {
  const sockets = [{ id: "stale-a" }, { id: "stale-b" }];
  assert.equal(selectHealthiestSocket(sockets, () => 35_001, 35_000), null);
});

test("ignores sockets without a finite heartbeat age", () => {
  const sockets = [{ id: "unknown" }, { id: "healthy" }];
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
