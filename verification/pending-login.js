const mc = require('minecraft-protocol');
const net = require('node:net');
const {randomUUID} = require('node:crypto');
const assert = require('node:assert/strict');
const clients = [];
function stalled(username) {
  const client = new mc.Client(false, '1.20.1');
  clients.push(client);
  const socket = net.connect({host: '127.0.0.1', port: 25585});
  client.setSocket(socket);
  client.on('error', error => { client.testError = error.message; });
  client.on('packet', (data, meta) => { if (['disconnect','kick_disconnect'].includes(meta.name)) client.reason = data.reason; });
  socket.once('connect', () => {
    client.write('set_protocol', {protocolVersion: 763, serverHost:'127.0.0.1', serverPort:25585, nextState:2});
    client.state = mc.states.LOGIN;
    client.write('login_start', {username, playerUUID:randomUUID()});
  });
  return client;
}
function event(client, name) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(Error('Timed out waiting for '+name)), 18000);
    client.once(name, data => {clearTimeout(timer);resolve(data);});
    client.once('error', e => {clearTimeout(timer);reject(e);});
  });
}
(async () => {
  const first = stalled('PendingOne');
  await event(first, 'encryption_begin');
  const start = Date.now();
  const expired = event(first, 'end');
  const second = stalled('PendingTwo');
  await event(second, 'end');
  assert.ok(JSON.stringify(second.reason)?.includes('디스코드에서 문의 티켓'), 'Pending capacity rejection must send its message');
  await expired;
  const elapsed = Date.now() - start;
  assert.ok(elapsed >= 8000 && elapsed < 15000, 'Plugin 10-second timeout should be used: '+elapsed);
  assert.ok(JSON.stringify(first.reason)?.includes('디스코드에서 문의 티켓'), 'Timeout must send its message');
  const third = stalled('PendingThree');
  await event(third, 'encryption_begin');
  console.log(JSON.stringify({version:'1.20.1',capacityRejectedWithMessage:true,timeoutRejectedWithMessage:true,capacityRecovered:true,elapsedMs:elapsed}));
})().catch(error=>{console.error(error);process.exitCode=1;}).finally(()=>{
  for (const client of clients) { client.end('test complete'); client.socket?.destroy(); }
  setTimeout(() => process.exit(process.exitCode || 0), 200);
});
