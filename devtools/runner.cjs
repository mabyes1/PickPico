'use strict';
// One Android process per job: cwd, exit, memory and native stdout belong to this job.
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const cfg = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
// process.exit() terminates the embedded Android process before JNI returns.
// Persist its code synchronously so the controller can distinguish it from a crash.
process.on('exit', code => {
  if (cfg.exitFile) fs.writeFileSync(cfg.exitFile, JSON.stringify({exitCode:code, completedAt:Date.now()}));
});
function result(value) {
  const temporary = cfg.resultFile + '.tmp';
  fs.writeFileSync(temporary, JSON.stringify(value));
  fs.renameSync(temporary, cfg.resultFile);
}
async function main() {
  if (cfg.operation === 'install') {
    const Arborist = require('./node_modules/npm/node_modules/@npmcli/arborist');
    const arb = new Arborist({ path: process.cwd(), cache: cfg.cache,
      registry: 'https://registry.npmjs.org/', ignoreScripts: true,
      binLinks: false, audit: false, fund: false, engineStrict: true,
      fetchRetries: 1, fetchTimeout: 30000 });
    console.log('Installing dependencies; lifecycle scripts and native compilation are disabled.');
    const tree = await arb.reify();
    const unsupported = [];
    for (const node of tree.inventory.values()) {
      if (node.isRoot) continue;
      if (fs.existsSync(path.join(node.path, 'binding.gyp')) || node.package.gypfile)
        unsupported.push({ name: node.name, reason: 'native_build_required' });
      else if (['preinstall', 'install', 'postinstall'].some(key => node.package.scripts?.[key]))
        unsupported.push({ name: node.name, reason: 'lifecycle_script_skipped' });
    }
    result({ installed: true, compatible: unsupported.length === 0, unsupported,
      lockfile: 'package-lock.json', scriptsExecuted: false });
    console.log(JSON.stringify({ installed: true, unsupported }));
    if (unsupported.length) process.exitCode = 2;
  } else if (cfg.operation === 'run') {
    process.argv = ['node', cfg.entry, ...(cfg.args || [])];
    await import(pathToFileURL(cfg.entry).href);
  } else if (cfg.operation === 'tunnel') {
    const localtunnel = require('localtunnel');
    const tunnel = await localtunnel({ port: cfg.port, local_host: '127.0.0.1', host: 'https://localtunnel.me' });
    result({ url: tunnel.url, status: 'allocated', externallyVerified: false,
      expiresAt: new Date(Date.now() + cfg.ttlSeconds * 1000).toISOString(),
      note: 'Provider may require a visitor consent page. Verify from an external client before sharing.' });
    console.log('Tunnel allocated:', tunnel.url);
    tunnel.on('error', error => { console.error(error.stack || error); process.exitCode = 1; tunnel.close(); });
    tunnel.on('close', () => { result({ url: tunnel.url, status: 'closed', externallyVerified: false }); });
    const timer = setTimeout(() => { tunnel.close(); }, cfg.ttlSeconds * 1000);
    const ownerTimer = setInterval(() => {
      try {
        const owner = JSON.parse(fs.readFileSync(cfg.serviceStatusFile, 'utf8'));
        if (owner.status !== 'running' || fs.existsSync(cfg.serviceCancelFile)) return tunnel.close();
        process.kill(owner.pid, 0);
      } catch { tunnel.close(); }
    }, 1000);
    tunnel.on('close', () => { clearTimeout(timer); clearInterval(ownerTimer); });
  } else throw new Error('Unsupported operation: ' + cfg.operation);
}
main().catch(error => {
  console.error(error.stack || error);
  result({ isError: true, error: error.message });
  process.exitCode = 1;
});
