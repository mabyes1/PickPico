'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const runner = path.resolve(__dirname, '../runner.cjs');
function execute(source) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(),'pico-dev-test-'));
  fs.writeFileSync(path.join(dir,'entry.cjs'), source);
  fs.writeFileSync(path.join(dir,'config.json'), JSON.stringify({operation:'run', entry:path.join(dir,'entry.cjs'),args:['hello'],resultFile:path.join(dir,'result.json'),exitFile:path.join(dir,'exit.json')}));
  const output=spawnSync(process.execPath,[runner,path.join(dir,'config.json')],{cwd:dir,encoding:'utf8',timeout:10000});
  return {dir,output};
}
test('entry sees its cwd and argv and completes normally',()=>{
  const {dir,output}=execute("console.log(JSON.stringify({cwd:process.cwd(),arg:process.argv[2]}))");
  assert.equal(output.status,0);assert.deepEqual(JSON.parse(output.stdout),{cwd:dir,arg:'hello'});
});
test('runtime exception is actionable stderr and nonzero exit',()=>{
  const {output}=execute("throw new Error('broken-fixture')");
  assert.equal(output.status,1);assert.match(output.stderr,/broken-fixture/);assert.match(output.stderr,/entry.cjs/);
});
test('explicit exit code preserved',()=>{
  const {output}=execute("console.error('test failure');process.exitCode=7");
  assert.equal(output.status,7);assert.match(output.stderr,/test failure/);
});
test('immediate process exit persists code before Android process disappears',()=>{
  const {dir,output}=execute("process.exit(7)");
  assert.equal(output.status,7);
  assert.equal(JSON.parse(fs.readFileSync(path.join(dir,'exit.json'),'utf8')).exitCode,7);
});
