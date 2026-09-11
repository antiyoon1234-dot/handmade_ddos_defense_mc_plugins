const mc = require('minecraft-protocol');
const assert = require('node:assert/strict');
const version = process.argv[2] || '1.20.1';
const port = Number(process.argv[3] || 25585);
if (![25585,25586].includes(port)) throw Error('Local test endpoints only');
const clients = [];
function client(name) {
  const c = mc.createClient({host: '127.0.0.1', port, version, username: name, auth: 'offline', disableChatSigning: true});
  clients.push(c); c.closed = false;
  c.on('error', error => { c.testError = error.message; });
  c.on('end', () => { c.closed = true; });
  c.on('packet', (data, meta) => { if (['disconnect','kick_disconnect'].includes(meta.name)) c.reason = data.reason; });
  c.on('position', p => c.write('teleport_confirm', {teleportId:p.teleportId}));
  return c;
}
function waitEvent(c, name) {
  return new Promise((resolve,reject) => {
    const timeout = setTimeout(() => reject(Error('timeout waiting '+name)), 15000);
    c.once(name, () => {clearTimeout(timeout);resolve();});
    c.once('error', e => {clearTimeout(timeout);reject(e);});
  });
}
(async()=>{
  const normal=client('LoginNormal'); await waitEvent(normal,'login');
  const rejected=client('LoginLimited'); await waitEvent(rejected,'end');
  const reason=JSON.stringify(rejected.reason);
  assert.ok(reason?.includes('서버 보호를 위해 연결이 일시적으로 종료되었습니다.'), reason);
  assert.equal(normal.closed,false);
  console.log(JSON.stringify({version,loginLimitMessageDelivered:true,existingSessionPreserved:true,reason:rejected.reason}));
})().catch(e=>{console.error(e);process.exitCode=1;}).finally(()=>{
  for (const client of clients) { if (!client.closed) client.end('test complete'); client.socket?.destroy(); }
  setTimeout(() => process.exit(process.exitCode || 0), 200);
});
