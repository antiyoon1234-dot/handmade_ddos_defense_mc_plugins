const mc = require('minecraft-protocol');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const version = process.argv[2] || '1.20.1';
const port = Number(process.argv[3] || 25585);
const interval = Number(process.argv[5] || 70);
const kind = process.argv[6] || 'command';
if (!['command', 'payload'].includes(kind)) throw Error('Unsupported test kind');
if (interval < 70 || interval > 1000) throw Error('Bounded local test rate only');
if (![25585, 25586].includes(port) || !['1.20.1', '1.20.6'].includes(version)) throw Error('Local test endpoints only');
const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
const clients = [];
async function connect(username) {
  const client = mc.createClient({host: '127.0.0.1', port, username, version, auth: 'offline', disableChatSigning: true});
  clients.push(client);
  client.closed = false;
  client.kickReason = null;
  client.on('error', error => { client.testError = error.message; });
  client.on('end', () => { client.closed = true; });
  client.on('packet', (packet, meta) => {
    if (meta.name === 'kick_disconnect' || meta.name === 'disconnect') client.kickReason = packet.reason;
  });
  client.on('position', packet => {
    client.write('teleport_confirm', {teleportId: packet.teleportId});
  });
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(Error(`${username}: login timed out`)), 15000);
    client.once('login', () => { clearTimeout(timer); resolve(); });
    client.once('error', error => { clearTimeout(timer); reject(error); });
    client.once('end', () => { clearTimeout(timer); reject(Error(`${username}: ended during login: ${JSON.stringify(client.kickReason)}`)); });
  });
  await wait(700);
  assert.equal(client.closed, false, `${username} should remain connected after login`);
  return client;
}
(async () => {
  const normal = await connect('GuardNormal');
  normal.chat('/help');
  const over = await connect('GuardOver');
  const start = Date.now();
  while (!over.closed && Date.now() - start < 6000) {
    if (kind === 'payload') over.write('custom_payload', {channel: 'test:noop', data: Buffer.from([0])});
    else over.chat('/help');
    await wait(interval);
  }
  await wait(300);
  assert.equal(over.closed, true, 'Repeated excessive commands should disconnect this session');
  const reason = JSON.stringify(over.kickReason);
  assert.ok(reason.includes('서버 보호를 위해 연결이 일시적으로 종료되었습니다.'), reason);
  assert.ok(reason.includes('디스코드에서 문의 티켓을 열어주세요.'), reason);
  assert.equal(normal.closed, false, 'Other player on the same IP must remain connected');
  const rejoined = await connect('GuardOver');
  rejoined.chat('/help');
  await wait(600);
  assert.equal(rejoined.closed, false, 'Kicked player must be able to reconnect and send a normal command');
  const result = {version, kind, endpoint: `127.0.0.1:${port}`, kickMessageDelivered: true, sameIpNormalPreserved: true, reconnectAfterKick: true, reason: over.kickReason};
  console.log(JSON.stringify(result, null, 2));
  if (process.argv[4]) fs.writeFileSync(process.argv[4], JSON.stringify(result, null, 2));
})().catch(error => { console.error(error); process.exitCode = 1; }).finally(async () => {
  for (const client of clients) if (!client.closed) client.end('test complete');
  await wait(200);
});
