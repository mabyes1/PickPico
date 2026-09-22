import test from 'node:test';
import assert from 'node:assert/strict';
import { offlineResult } from '../src/offline-result.js';
test('offline tool calls retain id and explicitly report no dispatch without guessing process death', () => {
  const r = offlineResult({id: 9, method: 'tools/call'}, 1, [42000]);
  assert.equal(r.id, 9);
  assert.equal(r.result.isError, true);
  assert.equal(r.result.structuredContent.reason, 'heartbeat_stale');
  assert.equal(r.result.structuredContent.commandDispatched, false);
  assert.equal(r.result.structuredContent.causeConfidence, 'unknown');
});
test('no socket and non-tool RPCs return structured offline error', () => {
  const r = offlineResult({id: 1, method: 'initialize'}, 0, []);
  assert.equal(r.error.code, -32001);
  assert.equal(r.error.data.lastHeartbeatAgeMs, null);
  assert.equal(r.error.data.reason, 'no_device_connection');
});
