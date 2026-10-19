// Fixed benchmark fixture v1. No dependencies. Run only inside a dedicated run folder.
const http = require('http');
const fs = require('fs');
const path = require('path');
const stateFile = path.join(__dirname, 'state.json');
const state = fs.existsSync(stateFile) ? JSON.parse(fs.readFileSync(stateFile, 'utf8')) : {
  fixtureVersion: 1, recipient: null, pickup: null, recipientSaves: 0,
  pickupSaves: 0, submitCount: 0, orders: [], humanCode: ''
};
const persist = () => fs.writeFileSync(stateFile, JSON.stringify(state, null, 2));
const json = (res, status, data) => {
  res.writeHead(status, {'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store'});
  res.end(JSON.stringify(data));
};
const server = http.createServer(async (req, res) => {
  const origin = req.headers.origin;
  if (origin && origin !== `http://${req.headers.host}`) return json(res, 403, {error:'foreign origin'});
  if (req.method === 'GET' && req.url === '/') {
    res.writeHead(200, {'Content-Type':'text/html; charset=utf-8','Cache-Control':'no-store'});
    return res.end(fs.readFileSync(path.join(__dirname, 'index.html')));
  }
  if (req.method === 'GET' && req.url === '/state') return json(res, 200, state);
  if (req.method !== 'POST') return json(res, 404, {error:'not found'});
  req.setEncoding('utf8');
  let raw = '';
  for await (const chunk of req) {
    raw += chunk;
    if (raw.length > 16384) return json(res, 413, {error:'too large'});
  }
  let body;
  try { body = JSON.parse(raw); } catch { return json(res, 400, {error:'invalid JSON'}); }
  if (req.url === '/recipient') {
    state.recipient = {name:String(body.name), phone:String(body.phone)}; state.recipientSaves++;
  } else if (req.url === '/pickup') {
    state.pickup = {locker:Number(body.locker), note:String(body.note)}; state.pickupSaves++;
  } else if (req.url === '/human') {
    state.humanCode = String(body.code);
  } else if (req.url === '/submit') {
    if (!state.recipient || !state.pickup) return json(res, 409, {error:'save both sections first'});
    state.submitCount++;
    state.orders.push({id:`TEST-${String(state.submitCount).padStart(3,'0')}`, recipient:{...state.recipient}, pickup:{...state.pickup}});
    persist();
    // The operation has happened. Its response deliberately arrives after client timeout.
    if (body.simulateTimeout) return setTimeout(() => { if (!res.destroyed) json(res,200,state); },5000);
  } else return json(res,404,{error:'not found'});
  persist(); json(res,200,state);
});
server.listen(0,'127.0.0.1',() => {
  console.log(JSON.stringify({fixtureVersion:1,url:`http://127.0.0.1:${server.address().port}`,pid:process.pid}));
});
