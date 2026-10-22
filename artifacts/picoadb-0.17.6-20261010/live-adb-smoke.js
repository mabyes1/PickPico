'use strict';
// Run through PickPico node.start. Authentication stays inside the phone.
const fs = require('fs');
const http = require('http');
const https = require('https');
const crypto = require('crypto');
const path = require('path');
const root = '/data/user/0/com.mcpocket.poc/files/workspaces';
const dir = path.join(root, 'validation-0176');
fs.mkdirSync(dir, {recursive: true});
const reportFile = path.join(dir, 'live-adb-smoke.json');
const xml = fs.readFileSync('/data/user/0/com.mcpocket.poc/shared_prefs/mcpocket_node.xml', 'utf8');
const token = xml.match(/<string name="token">([^<]+)<\/string>/)[1];
const report = {startedAt: new Date().toISOString(), checks: []};
function save() { fs.writeFileSync(reportFile, JSON.stringify(report, null, 2)); }
function check(name, passed, detail) { report.checks.push({name, passed, detail}); save(); if (!passed) throw new Error(name); }
function request(url, body, auth) {
  return new Promise((resolve, reject) => {
    const payload = body && JSON.stringify(body);
    const req = (url.startsWith('https:') ? https : http).request(url, {method: payload ? 'POST' : 'GET', headers: payload ? {'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(payload), ...(auth ? {Authorization: 'Bearer ' + token} : {})} : {}}, res => {
      const parts = []; res.on('data', b => parts.push(b)); res.on('end', () => {
        if (res.statusCode !== 200) return reject(new Error('HTTP ' + res.statusCode));
        const raw = Buffer.concat(parts); resolve(payload ? JSON.parse(raw.toString()) : raw);
      });
    });
    req.setTimeout(145000, () => req.destroy(new Error('request timeout')));
    req.on('error', reject); req.end(payload);
  });
}
let seq = 0;
async function rpc(method, params, remote = false) {
  const r = await request(remote ? 'https://relay.pickpico.workers.dev/v4/nodes/wjLVc8x8sGvNvNRzjYOhtQ/mcp' : 'http://127.0.0.1:8765/mcp', {jsonrpc:'2.0', id:++seq, method, ...(params ? {params} : {})}, !remote);
  if (r.error) throw new Error(JSON.stringify(r.error));
  return r.result;
}
async function call(name, args = {}) {
  const r = await rpc('tools/call', {name, arguments:{agent:'GPT-6', ...args}});
  let v = r.structuredContent;
  if (!v) v = JSON.parse(r.content.find(c => c.type === 'text').text);
  return v.result || v;
}
const shell = command => call('picoadb_shell', {command});
const exec = args => call('picoadb_exec', {args, timeoutMs:60000});
const hash = data => crypto.createHash('sha256').update(data).digest('hex');
(async () => {
  for (const remote of [false, true]) {
    const list = await rpc('tools/list', null, remote);
    const names = list.tools.map(t => t.name);
    check(remote ? 'relay exposes ADB tools' : 'loopback exposes ADB tools', ['picoadb_shell','picoadb_exec'].every(n => names.includes(n)), names.filter(n => n.startsWith('picoadb_')));
  }
  const status = await call('picoadb_status');
  report.before = {paired:status.paired, connected:status.connected, lastPairAt:status.lastPairAt, connection:status.connection}; save();
  const id = await shell('id');
  check('ADB shell UID 2000', id.success && /uid=2000\(shell\)/.test(id.output), id);
  const failure = await shell('printf pico-exit-test; exit 7');
  check('remote exit status preserved', !failure.success && failure.isError && failure.exitCode === 7 && failure.output === 'pico-exit-test', failure);
  const source = Buffer.from('PickPico ADB round trip\n繁體中文 測試 🐾\n', 'utf8');
  const sourceRel = 'validation-0176/push source.txt';
  const pulledRel = 'validation-0176/pulled copy.txt';
  const remoteFile = '/data/local/tmp/pico-adb-0176-validation.txt';
  const fixturePackage = 'com.mcpocket.adbsmoke';
  let createdRemote = false, installedFixture = false;
  try {
    const existingFile = await shell('[ -e ' + remoteFile + ' ]');
    check('roundtrip path was unused', existingFile.exitCode === 1, {exitCode: existingFile.exitCode});
    fs.writeFileSync(path.join(root, sourceRel), source);
    const push = await exec(['push', sourceRel, remoteFile]);
    createdRemote = push.success;
    check('push file', push.success, push);
    const pull = await exec(['pull', remoteFile, pulledRel]);
    check('pull file', pull.success, pull);
    const actual = fs.readFileSync(path.join(root, pulledRel));
    check('binary roundtrip matches SHA-256', hash(source) === hash(actual), {bytes: actual.length, sha256: hash(actual)});
    const absent = await shell('pm path ' + fixturePackage);
    check('fixture package was not installed', absent.exitCode === 1 && !absent.output.includes('package:'), {exitCode:absent.exitCode});
    const apk = await request('http://192.168.0.20:18766/fixture/pico-adb-fixture.apk');
    check('fixture APK hash', hash(apk) === '9e4c02d65ca8b8e22940dc519ca27c02ae9a2dc8e2098834249840858cfa28ae', {bytes:apk.length, sha256:hash(apk)});
    const fixtureRel = 'validation-0176/pico-adb-fixture.apk';
    fs.writeFileSync(path.join(root, fixtureRel), apk);
    const install = await exec(['install', fixtureRel]);
    installedFixture = install.success;
    check('install APK', install.success && install.output.includes('Success'), install);
    const installed = await shell('pm path ' + fixturePackage);
    check('package installed in Android', installed.success && installed.output.includes('package:'), {success:installed.success, exitCode:installed.exitCode});
  } finally {
    if (installedFixture) {
      const uninstall = await exec(['uninstall', fixturePackage]);
      check('remove only fixture package', uninstall.success && uninstall.output.includes('Success'), uninstall);
      const removed = await shell('pm path ' + fixturePackage);
      check('fixture no longer installed', removed.exitCode === 1 && !removed.output.includes('package:'), {exitCode:removed.exitCode});
    }
    if (createdRemote) {
      const cleanup = await shell('rm -- ' + remoteFile);
      check('remove roundtrip fixture from device', cleanup.success, {success:cleanup.success});
    }
  }
  const after = await call('picoadb_status');
  report.after = {paired:after.paired, connected:after.connected, lastPairAt:after.lastPairAt, connection:after.connection};
  check('pairing preserved', after.paired && after.lastPairAt === '2026-10-08T04:34:14.676352Z', {paired:after.paired, lastPairAt:after.lastPairAt});
  report.success = true;
})().catch(e => { report.success = false; report.error = String(e.message || e); process.exitCode = 1; }).finally(() => { report.finishedAt = new Date().toISOString(); save(); console.log(JSON.stringify({success:report.success, checks:report.checks.map(c => ({name:c.name,passed:c.passed})), error:report.error})); });
